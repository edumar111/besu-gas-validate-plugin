#!/usr/bin/env bash
# E2E de Fase 2: cuota mensual on-chain + bloqueo 5 min + commit del recorder.
#
# Flujo:
#   Stage A — Besu con JAR nuevo + cuota mensual BASIC chica (50K), SIN recorder:
#     - Deploya UsageMeter (recorder = cuenta RECORDER).
#     - setTier ALICE=BASIC, whitelist RECORDER.
#     - Envía transfers de 21K desde ALICE hasta exceder los 50K mensuales → verifica bloqueo.
#     - Verifica gasMembership_getMonthlyUsage.
#   Stage B — reinicia Besu CON recorder=true + meterContract + commitEveryBlocks=2:
#     - El recorder firma y envía recordUsageBatch al UsageMeter.
#     - Verifica que UsageMeter.getUsage on-chain refleja el uso (persistencia).
#
# Requisitos: JDK 21, Besu falcon, Foundry (forge/cast), plugin built (cd plugin && ./gradlew build).
# Uso: ./node/e2e-fase2.sh
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "${SCRIPT_DIR}/.." && pwd )"
RPC=http://localhost:4545
export PATH="/Users/edumar111/.foundry/bin:$PATH"
LOG_A=/tmp/besu-e2e2-stage-a.log
LOG_B=/tmp/besu-e2e2-stage-b.log

# === Cuentas (Hardhat deterministas, NO mainnet) ===
DEPLOYER_PK=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d  # owner registry (#1)
ALICE_PK=0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a    # BASIC (#2)
RECORDER_PK=0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a # recorder (#3)

DEPLOYER=$(cast wallet address $DEPLOYER_PK)
ALICE=$(cast wallet address $ALICE_PK)
RECORDER=$(cast wallet address $RECORDER_PK)

REGISTRY=0x948B3c65b89DF0B4894ABE91E6D02FE579834F8F  # ya deployado, owner = DEPLOYER
MONTHLY_BASIC=50000   # cuota mensual BASIC chica para forzar el exceso con transfers de 21K

JAR_GLOB=("${REPO_ROOT}/plugin/build/libs/besu-gas-membership-plugin-"*.jar)
JAR_PATH="${JAR_GLOB[0]}"
[ -f "$JAR_PATH" ] || { echo "ERROR: plugin JAR no encontrado; corré 'cd plugin && ./gradlew build'"; exit 1; }

cleanup_node() { pkill -f "besu.*--data-path=${REPO_ROOT}/node/data" 2>/dev/null || true; sleep 2; }
wait_rpc() {
  local pid=$1 log=$2
  for i in $(seq 1 60); do
    sleep 1
    kill -0 $pid 2>/dev/null || { echo "ERROR: Besu murió"; tail -40 "$log"; exit 1; }
    /usr/bin/curl -fsS -X POST $RPC -H 'Content-Type: application/json' \
      -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}' >/dev/null 2>&1 && return 0
  done
  echo "ERROR: RPC no respondió"; tail -40 "$log"; exit 1
}
rpc() { /usr/bin/curl -s -X POST $RPC -H 'Content-Type: application/json' --data "$1"; }

trap cleanup_node EXIT
echo "=== Parando nodo actual y instalando JAR nuevo (Fase 2) ==="
cleanup_node
mkdir -p "${REPO_ROOT}/node/data/plugins"
rm -f "${REPO_ROOT}/node/data/plugins/"*.jar
cp "$JAR_PATH" "${REPO_ROOT}/node/data/plugins/"
echo "  JAR: $(basename "$JAR_PATH") ($(wc -c < "$JAR_PATH") bytes)"

###############################################################################
echo ""
echo "########## STAGE A: enforcement mensual + bloqueo (sin recorder) ##########"
cd "${REPO_ROOT}/node"
export MEMBERSHIP_CONTRACT="$REGISTRY"
export BESU_OPTS="-Dratelimit.membershipContract=${REGISTRY} -Dratelimit.gas.monthly.basic=${MONTHLY_BASIC}"
./start-besu.sh > "$LOG_A" 2>&1 &
BESU_PID=$!
wait_rpc $BESU_PID "$LOG_A"
sleep 5
grep -q "GasMembershipPlugin: registrado" "$LOG_A" || { echo "ERROR: plugin no registró"; tail -30 "$LOG_A"; exit 1; }
echo "  plugin: $(grep 'registrado (contract=' "$LOG_A" | sed 's/^.*INFO //' | head -1)"
grep -q "Fase 2 activa" "$LOG_A" && echo "  $(grep 'Fase 2 activa' "$LOG_A" | sed 's/^.*INFO //' | head -1)" || echo "  WARN: Fase 2 no activa"

echo ""
echo "=== Preparar tiers: ALICE=BASIC, RECORDER=WHITELISTED ==="
cast send "$REGISTRY" "setTierBatch(address[],uint8[])" "[$ALICE,$RECORDER]" "[1,4]" \
  --rpc-url $RPC --private-key $DEPLOYER_PK --legacy >/dev/null
echo "  ALICE    tier=$(cast call "$REGISTRY" "getTier(address)(uint8)" $ALICE --rpc-url $RPC) (esperado 1=BASIC)"
echo "  RECORDER tier=$(cast call "$REGISTRY" "getTier(address)(uint8)" $RECORDER --rpc-url $RPC) (esperado 4=WHITELISTED)"

echo ""
echo "=== Deploy UsageMeter (owner=DEPLOYER, recorder=RECORDER) ==="
DEPLOY_OUT=$(cd "${REPO_ROOT}/contracts" && OWNER=$DEPLOYER RECORDER=$RECORDER \
  forge script script/DeployUsageMeter.s.sol --rpc-url $RPC --private-key $DEPLOYER_PK \
  --broadcast --legacy --skip-simulation 2>&1)
METER=$(echo "$DEPLOY_OUT" | grep "UsageMeter deployed at:" | awk '{print $NF}')
echo "  UsageMeter=$METER  recorder on-chain=$(cast call "$METER" "recorder()(address)" --rpc-url $RPC)"

echo ""
echo "=== Enviar transfers de ALICE (21K c/u) hasta exceder cuota mensual ${MONTHLY_BASIC} ==="
mu() { rpc "{\"jsonrpc\":\"2.0\",\"method\":\"gasMembership_getMonthlyUsage\",\"params\":[\"$ALICE\"],\"id\":1}" | sed 's/.*"result"://;s/}}$/}/'; }
send_alice() {
  local label="$1"
  if out=$(cast send "$ALICE" --value 0 --gas-limit 21000 --rpc-url $RPC --private-key $ALICE_PK --legacy --timeout 8 2>&1); then
    local blk=$(echo "$out" | grep -E "^blockNumber" | awk '{print $NF}')
    printf "  [OK ] %-22s block=%s\n" "$label" "$blk"
  else
    printf "  [REJ] %-22s (%s)\n" "$label" "$(echo "$out" | grep -oiE 'monthly gas quota exceeded|account temporarily blocked|no active membership' | head -1)"
  fi
}
send_alice "tx1 (21K, OK)"
send_alice "tx2 (42K, OK)"
send_alice "tx3 (63K>50K, REJ+block)"
echo "  usage tras tx3: $(mu)"
send_alice "tx4 (blocked)"
echo ""
echo "=== Estado mensual de ALICE (RPC) ==="
echo "  $(mu)"

STAGE_A_METER=$METER
echo ""
echo "=== Detener Stage A ==="
kill $BESU_PID 2>/dev/null || true; wait $BESU_PID 2>/dev/null || true; sleep 2

###############################################################################
echo ""
echo "########## STAGE B: commit on-chain del recorder ##########"
export BESU_OPTS="-Dratelimit.membershipContract=${REGISTRY} \
  -Dratelimit.gas.monthly.basic=${MONTHLY_BASIC} \
  -Dratelimit.usage.meterContract=${STAGE_A_METER} \
  -Dratelimit.usage.recorder=true \
  -Dratelimit.usage.recorderKey=${RECORDER_PK} \
  -Dratelimit.usage.commitEveryBlocks=2 \
  -Dratelimit.nodeUrl=${RPC}"
./start-besu.sh > "$LOG_B" 2>&1 &
BESU_PID=$!
wait_rpc $BESU_PID "$LOG_B"
sleep 5
grep -q "nodo RECORDER" "$LOG_B" && echo "  $(grep 'nodo RECORDER' "$LOG_B" | sed 's/^.*INFO //' | head -1)" || echo "  WARN: recorder no activo"

echo ""
echo "=== Generar uso de ALICE para disparar un commit (cada 2 bloques) ==="
for n in 1 2 3 4; do
  cast send "$ALICE" --value 0 --gas-limit 21000 --rpc-url $RPC --private-key $ALICE_PK --legacy --timeout 8 >/dev/null 2>&1 \
    && echo "  tx$n enviada" || echo "  tx$n rechazada (bloqueo aún vigente — esperado si <5min)"
  sleep 3
done

echo ""
echo "=== Esperar commit del recorder y verificar UsageMeter on-chain ==="
sleep 8
PERIOD=$(rpc "{\"jsonrpc\":\"2.0\",\"method\":\"gasMembership_getMonthlyUsage\",\"params\":[\"$ALICE\"],\"id\":1}" | grep -oE '"periodId":[0-9]+' | head -1 | cut -d: -f2)
echo "  periodId actual = ${PERIOD:-?}"
if [ -n "${PERIOD:-}" ]; then
  ONCHAIN=$(cast call "$STAGE_A_METER" "getUsage(uint256,address)(uint256)" "$PERIOD" "$ALICE" --rpc-url $RPC 2>&1)
  echo "  UsageMeter.getUsage(period=$PERIOD, ALICE) = $ONCHAIN gas (on-chain)"
fi
echo "  commits en log: $(grep -c "recordUsageBatch enviado" "$LOG_B" 2>/dev/null || echo 0)"
grep "recordUsageBatch enviado" "$LOG_B" 2>/dev/null | tail -2 | sed 's/^.*INFO //' | sed 's/^/    /' || true

echo ""
echo "=== E2E Fase 2 completo. Logs: $LOG_A (A) $LOG_B (B). Besu sigue corriendo (PID $BESU_PID) ==="
trap - EXIT
