package com.lacnet.besu.gas.client;

import java.math.BigInteger;
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
 * Consulta {@code UsageMeter.getUsage(uint256 periodId, address account)} on-chain vía
 * {@link TransactionSimulationService} — un {@code eth_call} interno sin pasar por el JSON-RPC.
 *
 * <p>Lo usa el {@code MonthlyUsageTracker} como {@code BaselineLoader}: la primera vez que se toca
 * una cuenta en un período, este cliente trae el consumo ya commiteado on-chain (el baseline). Tras
 * un restart del nodo, esto recupera el uso real persistido por el recorder.
 *
 * <p>Tolerante a fallos: cualquier error (simulación inválida, retorno vacío) se mapea a {@code 0}.
 * Es la opción <em>lenient</em> y deliberada: si la lectura del UsageMeter falla, el enforcement
 * mensual degrada a "solo el pending visto desde el arranque" en vez de bloquear cuentas por un
 * problema de comunicación. El enforcement <b>per-block</b> (Fase 1) sigue protegiendo la red.
 * No cachea — el caching/rehidratación es responsabilidad del {@code MonthlyUsageTracker}.
 */
public class UsageMeterClient {

    private static final Logger LOG = LoggerFactory.getLogger(UsageMeterClient.class);

    /**
     * Selector ABI de {@code getUsage(uint256,address)} = keccak256("getUsage(uint256,address)")[0:4].
     * Hardcoded (verificado por {@code UsageMeter.t.sol::test_GetUsageSelectorMatchPluginHardcoded}).
     */
    private static final Bytes GET_USAGE_SELECTOR = Bytes.fromHexString("0x44202d6e");

    private static final EnumSet<SimulationParameters> READ_ONLY_FLAGS = EnumSet.of(
            SimulationParameters.ALLOW_EXCEEDING_BALANCE,
            SimulationParameters.ALLOW_FUTURE_NONCE,
            SimulationParameters.ALLOW_UNDERPRICED);

    private final TransactionSimulationService simulator;
    private final Address contractAddress;

    public UsageMeterClient(
            final TransactionSimulationService simulator, final Address contractAddress) {
        this.simulator = simulator;
        this.contractAddress = contractAddress;
    }

    /**
     * Consumo de gas ya commiteado on-chain para {@code account} en {@code periodId}.
     *
     * @param periodId período (mes calendario UTC; ver {@code PeriodClock})
     * @param account cuenta a consultar
     * @return gas acumulado on-chain, o {@code 0} si la lectura falla o no hay registro
     */
    public long getUsage(final long periodId, final Address account) {
        // calldata: selector || uint256(periodId) || 32-byte left-padded address
        Bytes payload = Bytes.concatenate(
                GET_USAGE_SELECTOR,
                Bytes32.leftPad(Bytes.ofUnsignedLong(periodId)),
                Bytes32.leftPad(account));

        ProcessableBlockHeader header;
        try {
            header = simulator.simulatePendingBlockHeader();
        } catch (RuntimeException e) {
            LOG.warn("UsageMeter: simulatePendingBlockHeader() falló; baseline de {} = 0", account, e);
            return 0L;
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
            LOG.warn("UsageMeter: simulate() lanzó para {}; baseline = 0", account, e);
            return 0L;
        }

        if (result.isEmpty() || result.get().isInvalid() || !result.get().isSuccessful()) {
            LOG.debug("UsageMeter: simulación no exitosa para {} (period {}); baseline = 0",
                    account, periodId);
            return 0L;
        }

        Bytes output = result.get().result().getOutput();
        if (output == null || output.isEmpty()) {
            return 0L;
        }

        // getUsage devuelve un uint256. Lo decodificamos y clampeamos a long (el uso mensual real
        // entra holgado en long; un valor patológicamente grande se trata como "tope").
        BigInteger value = output.toUnsignedBigInteger();
        if (value.bitLength() > 63) {
            LOG.warn("UsageMeter: uso on-chain de {} excede long ({}); clamp a Long.MAX_VALUE",
                    account, value);
            return Long.MAX_VALUE;
        }
        return value.longValueExact();
    }
}
