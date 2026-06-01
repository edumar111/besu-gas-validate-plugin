package com.lacnet.besu.gas.client;

import com.lacnet.besu.gas.model.Tier;
import java.util.EnumSet;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.data.TransactionSimulationResult;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService.SimulationParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consulta {@code MembershipRegistry.getTier(address)} on-chain vía
 * {@link TransactionSimulationService} — equivalente a un {@code eth_call} interno sin pasar por
 * el JSON-RPC del nodo.
 *
 * <p>El cliente es deliberadamente tolerante: cualquier falla (simulación inválida, retorno vacío,
 * valor fuera de rango) se mapea a {@link Tier#NONE}. La decisión de qué hacer con una cuenta
 * sin tier vive en el selector, no acá.
 *
 * <p>Esta clase NO cachea — el caching es responsabilidad de {@code TierCache}. Cada llamada acá
 * dispara una simulación.
 */
public class MembershipContractClient {

    private static final Logger LOG = LoggerFactory.getLogger(MembershipContractClient.class);

    /**
     * Selector ABI de {@code getTier(address)} = keccak256("getTier(address)")[0:4].
     * Hardcoded para no pagar el costo de keccak256 ni una dependencia de bouncycastle.
     */
    private static final Bytes GET_TIER_SELECTOR = Bytes.fromHexString("0xb45aae52");

    /**
     * Read-only call → permitimos saltarse las validaciones que aplican a TX reales (balance,
     * nonce, gas price), de lo contrario un sender sintético con balance 0 fallaría.
     */
    private static final EnumSet<SimulationParameters> READ_ONLY_FLAGS = EnumSet.of(
            SimulationParameters.ALLOW_EXCEEDING_BALANCE,
            SimulationParameters.ALLOW_FUTURE_NONCE,
            SimulationParameters.ALLOW_UNDERPRICED);

    private final TransactionSimulationService simulator;
    private final Address contractAddress;

    public MembershipContractClient(
            final TransactionSimulationService simulator, final Address contractAddress) {
        this.simulator = simulator;
        this.contractAddress = contractAddress;
    }

    /**
     * Devuelve el tier asignado a {@code account} en el contrato.
     * Si la simulación falla, el contrato no existe, o el valor on-chain está fuera de rango,
     * devuelve {@link Tier#NONE}.
     */
    public Tier getTier(final Address account) {
        // calldata: 4-byte selector || 32-byte left-padded address
        Bytes payload = Bytes.concatenate(GET_TIER_SELECTOR, Bytes32.leftPad(account));

        ProcessableBlockHeader header;
        try {
            header = simulator.simulatePendingBlockHeader();
        } catch (RuntimeException e) {
            LOG.warn("simulatePendingBlockHeader() falló; tratando {} como NONE", account, e);
            return Tier.NONE;
        }

        Optional<TransactionSimulationResult> result;
        try {
            result = simulator.simulate(
                    new ReadOnlyCall(contractAddress, payload),
                    Optional.empty(),
                    header,
                    OperationTracer.NO_TRACING,
                    READ_ONLY_FLAGS);
        } catch (RuntimeException e) {
            LOG.warn("simulate() lanzó para {}; tratando como NONE", account, e);
            return Tier.NONE;
        }

        if (result.isEmpty() || result.get().isInvalid() || !result.get().isSuccessful()) {
            LOG.debug("Simulación no exitosa para {}, devolviendo NONE", account);
            return Tier.NONE;
        }

        Bytes output = result.get().result().getOutput();
        if (output == null || output.isEmpty()) {
            return Tier.NONE;
        }

        // El retorno de getTier(address) es un uint8 padded a 32 bytes. El valor real está en el
        // último byte. Si el contrato devuelve algo más corto, lo intentamos igual con el último
        // byte disponible.
        int onChainValue = output.get(output.size() - 1) & 0xFF;
        try {
            return Tier.fromOnChainValue(onChainValue);
        } catch (IllegalArgumentException e) {
            LOG.warn("Tier on-chain fuera de rango ({}) para {}; tratando como NONE",
                    onChainValue, account);
            return Tier.NONE;
        }
    }
}
