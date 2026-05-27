/**
 * Script: deploy-as-basic
 *
 * Deploya un contrato `Storage` dummy desde una cuenta que esta registrada como
 * BASIC en el MembershipRegistry. Sirve para validar end-to-end que el plugin
 * de gas acepta un deploy real cuando la cuenta tiene tier BASIC y el consumo
 * de gas queda por debajo del limite (500_000).
 *
 * Pre-condicion: Besu local corriendo con el plugin de gas activo en
 *                node/data/plugins/. Si Besu corre sin el plugin, el deploy
 *                igual va a salir, pero no estaras validando el comportamiento
 *                del plugin.
 *
 * Uso:
 *    cd pruebas
 *    npm install            # solo la primera vez
 *    npm run deploy:basic
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
const BASIC_GAS_LIMIT = 500_000n;

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
    fail(
      `chainId esperado ${EXPECTED_CHAIN_ID}, encontrado ${net.chainId}. ` +
        "Estas conectado a la red correcta?"
    );
  }

  const ownerWallet = new ethers.Wallet(OWNER_PK, provider);
  const basicWallet = new ethers.Wallet(BASIC_PK, provider);

  console.log("===========================================");
  console.log("Deploy as BASIC tier");
  console.log("===========================================");
  console.log(`chainId:          ${net.chainId}`);
  console.log(`Owner:            ${ownerWallet.address}`);
  console.log(`Basic deployer:   ${basicWallet.address}`);
  console.log();

  // 2. Resolver / deployar el registry.
  const registryAddress = await resolveRegistry(ownerWallet);
  console.log(`Registry:         ${registryAddress}`);

  // 3. Asegurar tier BASIC para la cuenta deployer.
  const registry = loadRegistry(registryAddress, ownerWallet);
  await ensureBasicTier(registry, basicWallet.address);

  // 4. Deployar Storage firmado por la wallet BASIC.
  console.log();
  console.log(`Deployando Storage como ${basicWallet.address}...`);
  const StorageFactory = await ethers.getContractFactory("Storage", basicWallet);
  let storage;
  try {
    storage = await StorageFactory.deploy();
  } catch (e: any) {
    fail(
      "El deploy fue rechazado.\n" +
        "Posible causa: el plugin de gas rechazo la TX, o la cuenta no quedo como BASIC.\n" +
        `Detalle: ${e?.shortMessage ?? e?.message ?? e}`
    );
  }
  const deployTx = storage.deploymentTransaction();
  if (!deployTx) fail("La TX de deploy no se genero");
  console.log(`TX hash:          ${deployTx.hash}`);

  const receipt = await deployTx.wait();
  if (!receipt || receipt.status !== 1) {
    fail(`El deploy fallo (status=${receipt?.status})`);
  }
  const storageAddr = await storage.getAddress();

  // 5. Mostrar resultado.
  const gasUsed = receipt.gasUsed;
  const pct = ((Number(gasUsed) * 100) / Number(BASIC_GAS_LIMIT)).toFixed(1);

  console.log();
  console.log("===========================================");
  console.log("Resultado");
  console.log("===========================================");
  console.log(`Storage addr:     ${storageAddr}`);
  console.log(
    `Gas usado:        ${formatGas(gasUsed)} / ${formatGas(BASIC_GAS_LIMIT)} (${pct}% del limite BASIC)`
  );
  console.log(`Status:           OK`);
}

async function resolveRegistry(owner: Wallet): Promise<string> {
  if (REGISTRY_ADDRESS_ENV) {
    console.log(`Registry desde env: ${REGISTRY_ADDRESS_ENV}`);
    return REGISTRY_ADDRESS_ENV;
  }
  if (fs.existsSync(REGISTRY_ADDRESS_FILE)) {
    const addr = fs.readFileSync(REGISTRY_ADDRESS_FILE, "utf-8").trim();
    const code = await owner.provider!.getCode(addr);
    if (code !== "0x") {
      console.log(`Registry desde archivo: ${addr}`);
      return addr;
    }
    console.log(
      `.registry-address apunta a ${addr} pero no hay bytecode alli — redeployando.`
    );
  }

  if (!fs.existsSync(REGISTRY_ARTIFACT_PATH)) {
    fail(
      `Artefacto del MembershipRegistry no encontrado en:\n  ${REGISTRY_ARTIFACT_PATH}\n` +
        "Corre primero: cd contracts && forge build"
    );
  }

  console.log("MembershipRegistry no encontrado — deployando uno nuevo...");
  const artifact = JSON.parse(fs.readFileSync(REGISTRY_ARTIFACT_PATH, "utf-8"));
  const factory = new ethers.ContractFactory(
    artifact.abi,
    artifact.bytecode.object,
    owner
  );
  const registry = await factory.deploy();
  await registry.waitForDeployment();
  const addr = await registry.getAddress();
  fs.writeFileSync(REGISTRY_ADDRESS_FILE, addr);
  console.log(`Registry deployado en: ${addr}`);
  return addr;
}

function loadRegistry(address: string, signer: Wallet): Contract {
  const artifact = JSON.parse(fs.readFileSync(REGISTRY_ARTIFACT_PATH, "utf-8"));
  return new ethers.Contract(address, artifact.abi, signer);
}

async function ensureBasicTier(registry: Contract, target: string) {
  const current: bigint = await registry.getTier(target);
  if (current === BigInt(BASIC_TIER)) {
    console.log(`Tier de ${target}: BASIC (ya estaba)`);
    return;
  }
  console.log(
    `Tier de ${target}: ${tierName(Number(current))} → asignando BASIC...`
  );
  const tx = await registry.setTier(target, BASIC_TIER);
  await tx.wait();
  const updated: bigint = await registry.getTier(target);
  if (updated !== BigInt(BASIC_TIER)) {
    fail(`setTier no quedo como BASIC (quedo ${tierName(Number(updated))})`);
  }
  console.log(`Tier confirmado:  BASIC`);
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
