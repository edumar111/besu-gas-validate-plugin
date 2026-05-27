#!/usr/bin/env bash
# Smoke test end-to-end del plugin de gas + MembershipRegistry.
#
# Flujo:
#   1. Arranca Besu (sin plugin todavía — el JAR debe estar en node/data/plugins/ para Stage B).
#   2. Genera 6 cuentas de prueba (claves deterministas, NO usar en mainnet).
#   3. Deploya MembershipRegistry desde contracts/.
#   4. Asigna tiers: ALICE=BASIC, BOB=STANDARD, CAROL=PREMIUM, DAVE=WHITELISTED, MALLORY=sin tier.
#   5. Detiene Besu, lo reinicia con el plugin activado y -Dratelimit.membershipContract=...
#   6. Envía TXs de prueba desde cada cuenta y verifica el enforcement.
#
# Requisitos:
#   - JDK 21 (Homebrew openjdk@21), Besu 25.8.0-falcon en /Users/edumar111/tools/,
#     Foundry (forge/cast) en PATH.
#   - Plugin compilado: cd plugin && ./gradlew build.
#
# Uso:
#   ./node/smoke-test.sh
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "${SCRIPT_DIR}/.." && pwd )"
RPC=http://localhost:4545

export PATH="/Users/edumar111/.foundry/bin:$PATH"
LOG_A=/tmp/besu-smoke-stage-a.log
LOG_B=/tmp/besu-smoke-stage-b.log

# === Cuentas de prueba (deterministas, NO mainnet) ===
DEPLOYER_PK=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d
ALICE_PK=0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a
BOB_PK=0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6
CAROL_PK=0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a
DAVE_PK=0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba
MALLORY_PK=0x92db14e403b83dfe3df233f83dfa3a0d7096f21ca9b0d6d6b8d88b2b4ec1564e

DEPLOYER=$(cast wallet address $DEPLOYER_PK)
ALICE=$(cast wallet address $ALICE_PK)
BOB=$(cast wallet address $BOB_PK)
CAROL=$(cast wallet address $CAROL_PK)
DAVE=$(cast wallet address $DAVE_PK)
MALLORY=$(cast wallet address $MALLORY_PK)

# === Verificar que el plugin JAR exista ===
JAR_GLOB=("${REPO_ROOT}/plugin/build/libs/besu-gas-membership-plugin-"*.jar)
if [ ! -f "${JAR_GLOB[0]}" ]; then
  echo "ERROR: plugin JAR no encontrado. Corré 'cd plugin && ./gradlew build' primero." >&2
  exit 1
fi
JAR_PATH="${JAR_GLOB[0]}"

# Helper: kill cualquier besu colgado en este nodo.
cleanup_node() {
  pkill -f "besu.*--data-path=${REPO_ROOT}/node/data" 2>/dev/null || true
  sleep 1
}
trap cleanup_node EXIT
cleanup_node

# === STAGE A: Besu sin plugin → deploy + setTiers ===
echo "=== Stage A: arrancando Besu sin plugin ==="
# Mover el JAR fuera del plugins-dir para que Besu no lo cargue todavía.
mkdir -p "${REPO_ROOT}/node/data/plugins"
mv "${REPO_ROOT}/node/data/plugins/"*.jar /tmp/ 2>/dev/null || true

cd "${REPO_ROOT}/node"
unset BESU_OPTS
./start-besu.sh > "$LOG_A" 2>&1 &
BESU_PID=$!

for i in $(seq 1 60); do
  sleep 1
  if ! kill -0 $BESU_PID 2>/dev/null; then
    echo "ERROR: Besu murió en Stage A"; tail -40 "$LOG_A"; exit 1
  fi
  if curl -fsS -X POST $RPC -H 'Content-Type: application/json' \
       -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}' >/dev/null 2>&1; then
    break
  fi
done
echo "  RPC listo."

echo "=== Deploy MembershipRegistry ==="
DEPLOY_OUT=$(cd "${REPO_ROOT}/contracts" && forge script script/Deploy.s.sol \
  --rpc-url $RPC --private-key $DEPLOYER_PK --broadcast --legacy --skip-simulation 2>&1)
CONTRACT=$(echo "$DEPLOY_OUT" | grep "MembershipRegistry deployed at:" | awk '{print $NF}')
echo "  contract=$CONTRACT"

echo "=== Asignar tiers ==="
cast send "$CONTRACT" "setTierBatch(address[],uint8[])" \
  "[$ALICE,$BOB,$CAROL,$DAVE]" "[1,2,3,4]" \
  --rpc-url $RPC --private-key $DEPLOYER_PK --legacy > /dev/null
for who_addr in "ALICE:$ALICE" "BOB:$BOB" "CAROL:$CAROL" "DAVE:$DAVE" "MALLORY:$MALLORY"; do
  who=${who_addr%%:*}; addr=${who_addr##*:}
  tier=$(cast call "$CONTRACT" "getTier(address)(uint8)" $addr --rpc-url $RPC)
  echo "  $who → tier=$tier"
done

echo "=== Detener Stage A ==="
kill $BESU_PID 2>/dev/null || true
wait $BESU_PID 2>/dev/null || true
sleep 2

# === STAGE B: Besu CON plugin activo ===
echo "=== Stage B: arrancando Besu con plugin ==="
cp "$JAR_PATH" "${REPO_ROOT}/node/data/plugins/"
export BESU_OPTS="-Dratelimit.membershipContract=$CONTRACT"
./start-besu.sh > "$LOG_B" 2>&1 &
BESU_PID=$!

for i in $(seq 1 60); do
  sleep 1
  if ! kill -0 $BESU_PID 2>/dev/null; then
    echo "ERROR: Besu murió en Stage B"; tail -40 "$LOG_B"; exit 1
  fi
  if curl -fsS -X POST $RPC -H 'Content-Type: application/json' \
       -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}' >/dev/null 2>&1; then
    break
  fi
done
sleep 5 # margen para que el plugin termine de cablearse
echo "  RPC listo."
if ! grep -q "GasMembershipPlugin: registrado" "$LOG_B"; then
  echo "ERROR: plugin no se registró"; tail -40 "$LOG_B"; exit 1
fi
echo "  plugin registrado: $(grep 'registrado (contract=' "$LOG_B" | sed 's/^.*INFO //')"

# === Smoke test cases ===
send() {
  local label="$1" pk="$2" gas="$3"
  local from
  from=$(cast wallet address $pk)
  if out=$(cast send "$from" --value 0 --gas-limit "$gas" \
             --rpc-url $RPC --private-key "$pk" --legacy --timeout 8 2>&1); then
    block=$(echo "$out" | grep -E "^blockNumber" | awk '{print $NF}')
    printf "  [OK ] %-30s gas=%-10s block=%s\n" "$label" "$gas" "$block"
  else
    printf "  [REJ] %-30s gas=%-10s (rechazada por el selector)\n" "$label" "$gas"
  fi
}

echo "=== Casos ==="
send "MALLORY (NONE)"            $MALLORY_PK 21000
send "DAVE (WHITELISTED)"        $DAVE_PK    50000000
send "ALICE (BASIC, dentro)"     $ALICE_PK   21000
send "BOB (STANDARD, dentro)"    $BOB_PK     4000000
send "CAROL (PREMIUM, dentro)"   $CAROL_PK   8000000
send "ALICE (BASIC, > 500K)"     $ALICE_PK   600000
send "CAROL PREMIUM con sobrante" $CAROL_PK  50000000
send "ALICE (BASIC, 1M > 500K)"  $ALICE_PK   1000000

echo
echo "=== Smoke test completo. Log de Besu: $LOG_B ==="
echo "(Besu sigue corriendo; matalo con: kill $BESU_PID)"
