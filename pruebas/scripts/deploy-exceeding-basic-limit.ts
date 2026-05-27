/**
 * Script: deploy-exceeding-basic-limit
 *
 * Misma cuenta que deploy-as-basic (ALICE, ya registrada como BASIC) intenta
 * deployar un contrato `FatStorage` cuyo constructor consume ~700K gas. Como
 * el limite por bloque para BASIC es 500K, el `PluginTransactionPoolValidator`
 * rechaza la TX upfront -> `eth_sendRawTransaction` devuelve error -> ethers
 * tira excepcion en milisegundos. Sin polling, sin timeout.
 *
 * Pre-condiciones:
 *  - Besu corriendo con el plugin activo (incluyendo el validator de Fase 1.5)
 *    y la addr correcta del registry.
 *  - La cuenta BASIC_PK ya esta registrada como BASIC (corre antes
 *    `npm run deploy:basic` por lo menos una vez).
 *
 * Uso:
 *    cd pruebas
 *    npm run deploy:exceed
 */

import { ethers } from "hardhat";
import type { Contract, Wallet } from "ethers";
import * as fs from "fs";
import * as path from "path";
import * as dotenv from "dotenv";

dotenv.config();

const BASIC_PK =
  process.env.BASIC_PK ??
  "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a";
const REGISTRY_ADDRESS_ENV = process.env.REGISTRY_ADDRESS?.trim();

const EXPECTED_CHAIN_ID = 650540n;
const BASIC_TIER = 1;
const BASIC_GAS_LIMIT = 500_000n;
const TX_GAS_LIMIT = 800_000n;

const REGISTRY_ARTIFACT_PATH = path.resolve(
  __dirname,
  "../../contracts/out/MembershipRegistry.sol/MembershipRegistry.json"
);
const REGISTRY_ADDRESS_FILE = path.resolve(__dirname, "../.registry-address");

async function main() {
  const provider = ethers.provider;

  // 1. Validar conexion y chainId.
  let net;
  try {
    net = await provider.getNetwork();
  } catch (e) {
    fail(
      "No se pudo conectar al RPC. Asegurate de que Besu este corriendo:\n" +
        "  ./node/start-besu.sh"
    );
  }
  if (net.chainId !== EXPECTED_CHAIN_ID) {
    fail(`chainId esperado ${EXPECTED_CHAIN_ID}, encontrado ${net.chainId}.`);
  }

  const basicWallet = new ethers.Wallet(BASIC_PK, provider);

  console.log("===========================================");
  console.log("Caso negativo: deploy > limite BASIC");
  console.log("===========================================");
  console.log(`chainId:          ${net.chainId}`);
  console.log(`Deployer (BASIC): ${basicWallet.address}`);
  console.log();

  // 2. Resolver registry (sin deployar).
  const registryAddress = resolveRegistryAddressOrFail();
  console.log(`Registry:         ${registryAddress}`);

  // 3. Verificar que la cuenta es BASIC.
  const registry = loadRegistry(registryAddress, basicWallet);
  const current: bigint = await registry.getTier(basicWallet.address);
  if (current !== BigInt(BASIC_TIER)) {
    fail(
      `La cuenta ${basicWallet.address} no es BASIC (tier=${tierName(Number(current))}).\n` +
        "Si no esta registrada como BASIC, corre primero:\n" +
        "  npm run deploy:basic"
    );
  }
  console.log(`Tier confirmado:  BASIC`);

  // 4. Intentar deploy de FatStorage — el validator del plugin debe rechazarlo upfront.
  console.log();
  console.log(`Deployando FatStorage como ${basicWallet.address}...`);
  console.log(
    `Gas limit de la TX: ${formatGas(TX_GAS_LIMIT)} (mayor a limite BASIC ${formatGas(BASIC_GAS_LIMIT)})`
  );

  const FatStorageFactory = await ethers.getContractFactory(
    "FatStorage",
    basicWallet
  );
  const start = Date.now();
  try {
    const contract = await FatStorageFactory.deploy({ gasLimit: TX_GAS_LIMIT });
    // INESPERADO: el deploy se aceptó.
    const deployTx = contract.deploymentTransaction();
    console.log();
    console.log("===========================================");
    console.log("Resultado: TX ADMITIDA AL POOL (INESPERADO)");
    console.log("===========================================");
    console.log(`TX hash:          ${deployTx?.hash}`);
    console.log();
    console.log("El validator NO rechazo la TX en el submit. Revisa:");
    console.log("  - El JAR del plugin (con Fase 1.5) esta en node/data/plugins/");
    console.log("  - start-besu.sh tiene -Dratelimit.membershipContract correcto");
    console.log(
      `  - La cuenta ${basicWallet.address} realmente tiene tier BASIC,`
    );
    console.log("    no WHITELISTED ni PREMIUM");
    process.exit(1);
  } catch (e: any) {
    const elapsedMs = Date.now() - start;
    const detail = e?.shortMessage ?? e?.info?.error?.message ?? e?.message ?? String(e);
    printRejected(elapsedMs, detail);
  }
}

function resolveRegistryAddressOrFail(): string {
  if (REGISTRY_ADDRESS_ENV) {
    return REGISTRY_ADDRESS_ENV;
  }
  if (fs.existsSync(REGISTRY_ADDRESS_FILE)) {
    return fs.readFileSync(REGISTRY_ADDRESS_FILE, "utf-8").trim();
  }
  fail(
    "No encontre la addr del MembershipRegistry.\n" +
      "  - Seteala en .env (REGISTRY_ADDRESS=0x...) o\n" +
      "  - corre el bootstrap primero (ver README.md)."
  );
}

function loadRegistry(address: string, signer: Wallet): Contract {
  if (!fs.existsSync(REGISTRY_ARTIFACT_PATH)) {
    fail(
      `Artefacto del registry no encontrado: ${REGISTRY_ARTIFACT_PATH}\n` +
        "Corre: cd contracts && forge build"
    );
  }
  const artifact = JSON.parse(fs.readFileSync(REGISTRY_ARTIFACT_PATH, "utf-8"));
  return new ethers.Contract(address, artifact.abi, signer);
}

function printRejected(elapsedMs: number, detail: string) {
  const exceso = TX_GAS_LIMIT - BASIC_GAS_LIMIT;
  console.log();
  console.log("===========================================");
  console.log("Resultado: TX RECHAZADA AL SUBMIT (esperado)");
  console.log("===========================================");
  console.log(`Tiempo:           ${elapsedMs} ms`);
  console.log(`Gas limit TX:     ${formatGas(TX_GAS_LIMIT)}`);
  console.log(`Limite BASIC:     ${formatGas(BASIC_GAS_LIMIT)}`);
  console.log(
    `Exceso:           ${formatGas(exceso)} (+${((Number(exceso) / Number(BASIC_GAS_LIMIT)) * 100).toFixed(0)}%)`
  );
  console.log();
  console.log(`Razon del nodo:   ${detail}`);
  console.log();
  console.log(
    "El validator del plugin rechazo la TX en eth_sendRawTransaction antes"
  );
  console.log(
    "de que entre al txpool. Sin polling, sin timeout — error directo."
  );
}

function tierName(t: number): string {
  return (
    ["NONE", "BASIC", "STANDARD", "PREMIUM", "WHITELISTED"][t] ??
    `UNKNOWN(${t})`
  );
}

function formatGas(n: bigint): string {
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
