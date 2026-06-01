package com.lacnet.besu.gas.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.CallParameter;
import org.hyperledger.besu.datatypes.StateOverrideMap;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.data.TransactionProcessingResult;
import org.hyperledger.besu.plugin.data.TransactionSimulationResult;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsageMeterClientTest {

    private static final Address CONTRACT = Address.fromHexString("0x2222222222222222222222222222222222222222");
    private static final Address ACCOUNT = Address.fromHexString("0xabcdef0123456789abcdef0123456789abcdef01");
    private static final long PERIOD = 2026L * 12 + 4;

    @Mock private TransactionSimulationService simulator;
    @Mock private ProcessableBlockHeader header;
    @Mock private TransactionSimulationResult simulationResult;
    @Mock private TransactionProcessingResult processingResult;

    private UsageMeterClient client;

    @BeforeEach
    void setUp() {
        client = new UsageMeterClient(simulator, CONTRACT);
        lenient().when(simulator.simulatePendingBlockHeader()).thenReturn(header);
    }

    private void simulationReturns(final Bytes output) {
        when(simulationResult.isInvalid()).thenReturn(false);
        when(simulationResult.isSuccessful()).thenReturn(true);
        when(simulationResult.result()).thenReturn(processingResult);
        when(processingResult.getOutput()).thenReturn(output);
        when(simulator.simulate(
                        any(CallParameter.class), any(), any(ProcessableBlockHeader.class),
                        any(OperationTracer.class), any(EnumSet.class)))
                .thenReturn(Optional.of(simulationResult));
    }

    /** uint256 ABI-encoded a 32 bytes. */
    private static Bytes uint256(final long value) {
        return Bytes32.leftPad(Bytes.ofUnsignedLong(value));
    }

    @Test
    void decodificaUsoOnChain() {
        simulationReturns(uint256(1_234_567L));
        assertEquals(1_234_567L, client.getUsage(PERIOD, ACCOUNT));
    }

    @Test
    void devuelveCeroCuandoUsoEsCero() {
        simulationReturns(uint256(0L));
        assertEquals(0L, client.getUsage(PERIOD, ACCOUNT));
    }

    @Test
    void devuelveCeroCuandoSimulacionEsOptionalEmpty() {
        when(simulator.simulate(
                        any(CallParameter.class), any(), any(ProcessableBlockHeader.class),
                        any(OperationTracer.class), any(EnumSet.class)))
                .thenReturn(Optional.empty());
        assertEquals(0L, client.getUsage(PERIOD, ACCOUNT));
    }

    @Test
    void devuelveCeroCuandoSimulacionEsInvalid() {
        when(simulationResult.isInvalid()).thenReturn(true);
        when(simulator.simulate(
                        any(CallParameter.class), any(), any(ProcessableBlockHeader.class),
                        any(OperationTracer.class), any(EnumSet.class)))
                .thenReturn(Optional.of(simulationResult));
        assertEquals(0L, client.getUsage(PERIOD, ACCOUNT));
    }

    @Test
    void devuelveCeroCuandoOutputEsVacio() {
        simulationReturns(Bytes.EMPTY);
        assertEquals(0L, client.getUsage(PERIOD, ACCOUNT));
    }

    @Test
    void devuelveCeroCuandoSimulatorLanza() {
        when(simulator.simulate(
                        any(CallParameter.class), any(), any(ProcessableBlockHeader.class),
                        any(OperationTracer.class), any(EnumSet.class)))
                .thenThrow(new RuntimeException("nodo caído"));
        assertEquals(0L, client.getUsage(PERIOD, ACCOUNT));
    }

    @Test
    void valorFueraDeLongClampeaAMaxValue() {
        // uint256 con bit 64 seteado → no entra en long → clamp
        BigInteger huge = BigInteger.ONE.shiftLeft(64);
        simulationReturns(Bytes32.leftPad(Bytes.wrap(huge.toByteArray()).trimLeadingZeros()));
        assertEquals(Long.MAX_VALUE, client.getUsage(PERIOD, ACCOUNT));
    }

    @Test
    void abiEncodingTieneSelectorPeriodYAddress() {
        simulationReturns(uint256(42L));
        client.getUsage(PERIOD, ACCOUNT);

        ArgumentCaptor<CallParameter> callCaptor = ArgumentCaptor.forClass(CallParameter.class);
        verify(simulator).simulate(
                callCaptor.capture(),
                eq(Optional.<StateOverrideMap>empty()),
                eq(header),
                eq(OperationTracer.NO_TRACING),
                any(EnumSet.class));

        CallParameter sent = callCaptor.getValue();
        assertEquals(Optional.of(CONTRACT), sent.getTo());

        Bytes payload = sent.getPayload().orElseThrow();
        // 4 selector + 32 periodId + 32 address = 68 bytes
        assertEquals(68, payload.size(), "calldata debe ser 4 + 32 + 32 bytes");
        assertEquals("0x44202d6e", payload.slice(0, 4).toHexString(),
                "selector de getUsage(uint256,address)");
        // periodId en la primera palabra (uint256); los últimos 8 bytes contienen el valor
        assertEquals(PERIOD, payload.slice(28, 8).toLong());
        // address left-padded en la segunda palabra
        assertEquals(ACCOUNT.toUnprefixedHexString(),
                payload.slice(36 + 12, 20).toUnprefixedHexString());
    }
}
