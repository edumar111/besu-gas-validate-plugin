/**
 * Script: deploy-multi-tx-saturate-block
 *
 * Reproduce el caso de rechazo per-block del selector (que el validator NO cubre):
 * ALICE (BASIC, cuota 500K) manda dos llamadas a GasBurner.consumeGas que queman
 * gas REAL — una de ~350K y otra de ~300K. Sumadas superan los 500K de su cuota.
 *
 *   - TX-A (nonce N,   consumeGas 350K) → entra en el bloque actual.
 *   - TX-B (nonce N+1, consumeGas 300K) → en el mismo bloque proyecta > 500K →
 *     el selector la rechaza con invalidTransient → queda en el pool → entra
 *     en el bloque SIGUIENTE (cuando el contador per-block se resetea).
 *
 * El script pollea el método JSON-RPC custom `gasMembership_getRejection(txHash)`
 * de la TX-B para detectar el rechazo en tiempo (casi) real, y reporta en qué
 * bloque entró finalmente cada TX.
 *
 * IMPORTANTE: el accounting per-block usa gas REAL consumido (post-processing),
 * no el gasLimit declarado. Por eso usamos GasBurner — una transferencia común
 * usa solo 21K real y no dispararía la cuota.
 *
 * Pre-condiciones:
 *  - Besu corriendo con el plugin de Fase 1.6 (selector + validator + RPC custom).
 *  - BASIC_PK registrada como BASIC (corre antes `npm run deploy:basic`).
 *
 * Uso:
 *    cd pruebas
 *    npm run deploy:saturate
 */

import { ethers } from "hardhat";
import type { Contract, Wallet } from "ethers";
import * as fs from "fs";
import * as path from "path";
import * as dotenv from "dotenv";

dotenv.config();

const OWNER_PK =
  process.env.OWNER_PK ??
  "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";
const BASIC_PK =
  process.env.BASIC_PK ??
  "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a";
const REGISTRY_ADDRESS_ENV = process.env.REGISTRY_ADDRESS?.trim();

const EXPECTED_CHAIN_ID = 650540n;
const BASIC_TIER = 1;
const BURN_A = 350_000n;
const BURN_B = 300_000n;
const GAS_LIMIT_A = 450_000n;
const GAS_LIMIT_B = 400_000n;
const POLL_INTERVAL_MS = 1_000;
const POLL_TIMEOUT_MS = 30_000;

const REGISTRY_ARTIFACT_PATH = path.resolve(
  __dirname,
  "../../contracts/out/MembershipRegistry.sol/MembershipRegistry.json"
);
const GASBURNER_ARTIFACT_PATH = path.resolve(
  __dirname,
  "../../contracts/out/GasBurner.sol/GasBurner.json"
);
const REGISTRY_ADDRESS_FILE = path.resolve(__dirname, "../.registry-address");
const GASBURNER_ADDRESS_FILE = path.resolve(__dirname, "../.gasburner-address");

async function main() {
  const provider = ethers.provider;

  let net;
  try {
    net = await provider.getNetwork();
  } catch (e) {
    fail("No se pudo conectar al RPC. Arrancá Besu: ./node/start-besu.sh");
  }
  if (net.chainId !== EXPECTED_CHAIN_ID) {
    fail(`chainId esperado ${EXPECTED_CHAIN_ID}, encontrado ${net.chainId}.`);
  }

  const owner = new ethers.Wallet(OWNER_PK, provider);
  const alice = new ethers.Wallet(BASIC_PK, provider);

  console.log("===========================================");
  console.log("Saturación per-block: dos TX, mismo sender");
  console.log("===========================================");
  console.log(`chainId:          ${net.chainId}`);
  console.log(`Deployer (BASIC): ${alice.address}`);
  console.log();

  // Verificar tier BASIC.
  const registryAddress = resolveRegistryAddressOrFail();
  const registry = loadContract(REGISTRY_ARTIFACT_PATH, registryAddress, alice);
  const tier: bigint = await registry.getTier(alice.address);
  if (tier !== BigInt(BASIC_TIER)) {
    fail(
      `La cuenta ${alice.address} no es BASIC (tier=${tier}). Corré npm run deploy:basic primero.`
    );
  }
  console.log(`Tier confirmado:  BASIC (cuota 500 000 gas/bloque)`);

  // Resolver/deployar GasBurner con el owner (WHITELISTED, sin cuota).
  const burnerAddress = await resolveGasBurner(owner);
  console.log(`GasBurner:        ${burnerAddress}`);
  const burner = loadContract(GASBURNER_ARTIFACT_PATH, burnerAddress, alice);

  // Enviar las dos TX back-to-back con nonces explícitos (ambas al pool antes del bloque).
  const nonce = await provider.getTransactionCount(alice.address, "pending");
  console.log();
  console.log(`Enviando TX-A: consumeGas(${fmt(BURN_A)}) nonce=${nonce}`);
  const txA = await burner.consumeGas(BURN_A, { nonce, gasLimit: GAS_LIMIT_A });
  console.log(`  hash: ${txA.hash}`);
  console.log(`Enviando TX-B: consumeGas(${fmt(BURN_B)}) nonce=${nonce + 1}`);
  const txB = await burner.consumeGas(BURN_B, { nonce: nonce + 1, gasLimit: GAS_LIMIT_B });
  console.log(`  hash: ${txB.hash}`);

  // Pollear el RPC custom de TX-B para ver el rechazo per-block (puede ocurrir varias veces).
  console.log();
  console.log(`Polleando gasMembership_getRejection(TX-B) cada ${POLL_INTERVAL_MS / 1000}s...`);
  const start = Date.now();
  let rejectionSeen = false;
  while (Date.now() - start < POLL_TIMEOUT_MS) {
    const rej = await provider.send("gasMembership_getRejection", [txB.hash]);
    if (rej && !rejectionSeen) {
      rejectionSeen = true;
      const elapsed = ((Date.now() - start) / 1000).toFixed(1);
      console.log();
      console.log("===========================================");
      console.log("TX-B RECHAZADA POR EL SELECTOR (esperado)");
      console.log("===========================================");
      console.log(`Detectado en:     ${elapsed}s desde el envío`);
      console.log(`Bloque:           ${rej.blockNumber}`);
      console.log(`Sender:           ${rej.sender} (${rej.tier})`);
      console.log(`Razón:            ${rej.reason}`);
      console.log(`Gas usado bloque: ${fmt(BigInt(rej.usedInBlock))} / ${fmt(BigInt(rej.quota))}`);
      console.log(`Gas TX-B:         ${fmt(BigInt(rej.txGasLimit))}`);
      console.log(`Source:           ${rej.source}`);
    }
    // ¿Ya se minó TX-B? (entra en el bloque siguiente al reset del contador)
    const receiptB = await provider.getTransactionReceipt(txB.hash);
    if (receiptB) {
      break;
    }
    await sleep(POLL_INTERVAL_MS);
  }

  // Reporte final: en qué bloque entró cada una.
  const [rA, rB] = await Promise.all([txA.wait(), txB.wait()]);
  console.log();
  console.log("===========================================");
  console.log("Resultado final");
  console.log("===========================================");
  console.log(`TX-A minada en bloque: ${rA!.blockNumber} (gasUsed ${fmt(rA!.gasUsed)})`);
  console.log(`TX-B minada en bloque: ${rB!.blockNumber} (gasUsed ${fmt(rB!.gasUsed)})`);
  const gap = Number(rB!.blockNumber) - Number(rA!.blockNumber);
  if (gap >= 1) {
    console.log();
    console.log(`OK: TX-B entró ${gap} bloque(s) después que TX-A — diferida por la cuota per-block.`);
    if (!rejectionSeen) {
      console.log(
        "(No se capturó el evento de rechazo vía RPC — puede haber expirado del cache o el plugin no es Fase 1.6.)"
      );
    }
  } else {
    console.log();
    console.log("INESPERADO: ambas entraron en el mismo bloque. Revisá que:");
    console.log("  - El plugin esté cargado y la cuenta sea BASIC (no WHITELISTED).");
    console.log("  - GasBurner realmente queme el gas (artefacto compilado al día).");
    process.exit(1);
  }
}

async function resolveGasBurner(owner: Wallet): Promise<string> {
  if (fs.existsSync(GASBURNER_ADDRESS_FILE)) {
    const addr = fs.readFileSync(GASBURNER_ADDRESS_FILE, "utf-8").trim();
    const code = await owner.provider!.getCode(addr);
    if (code !== "0x") {
      return addr;
    }
  }
  if (!fs.existsSync(GASBURNER_ARTIFACT_PATH)) {
    fail(
      `Artefacto de GasBurner no encontrado: ${GASBURNER_ARTIFACT_PATH}\n` +
        "Corré: cd contracts && forge build"
    );
  }
  console.log("GasBurner no encontrado — deployando con el owner (WHITELISTED)...");
  const artifact = JSON.parse(fs.readFileSync(GASBURNER_ARTIFACT_PATH, "utf-8"));
  const factory = new ethers.ContractFactory(artifact.abi, artifact.bytecode.object, owner);
  const burner = await factory.deploy();
  await burner.waitForDeployment();
  const addr = await burner.getAddress();
  fs.writeFileSync(GASBURNER_ADDRESS_FILE, addr);
  return addr;
}

function resolveRegistryAddressOrFail(): string {
  if (REGISTRY_ADDRESS_ENV) {
    return REGISTRY_ADDRESS_ENV;
  }
  if (fs.existsSync(REGISTRY_ADDRESS_FILE)) {
    return fs.readFileSync(REGISTRY_ADDRESS_FILE, "utf-8").trim();
  }
  fail("No encontré la addr del MembershipRegistry. Corré el bootstrap (ver README.md).");
}

function loadContract(artifactPath: string, address: string, signer: Wallet): Contract {
  if (!fs.existsSync(artifactPath)) {
    fail(`Artefacto no encontrado: ${artifactPath}\nCorré: cd contracts && forge build`);
  }
  const artifact = JSON.parse(fs.readFileSync(artifactPath, "utf-8"));
  return new ethers.Contract(address, artifact.abi, signer);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function fmt(n: bigint): string {
  return n.toLocaleString("en-US").replace(/,/g, " ");
}

function fail(msg: string): never {
  console.error();
  console.error("ERROR: " + msg);
  process.exit(1);
}

main().catch((e) => {
  console.error("ERROR inesperado:");
  console.error(e);
  process.exit(1);
});
