package com.lacnet.besu.gas.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lacnet.besu.gas.model.Tier;
import java.util.EnumSet;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.CallParameter;
import org.hyperledger.besu.datatypes.StateOverrideMap;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.data.TransactionProcessingResult;
import org.hyperledger.besu.plugin.data.TransactionSimulationResult;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.hyperledger.besu.plugin.services.TransactionSimulationService.SimulationParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MembershipContractClientTest {

    private static final Address CONTRACT = Address.fromHexString("0x1234567890123456789012345678901234567890");
    private static final Address ACCOUNT = Address.fromHexString("0xabcdef0123456789abcdef0123456789abcdef01");

    @Mock private TransactionSimulationService simulator;
    @Mock private ProcessableBlockHeader header;
    @Mock private TransactionSimulationResult simulationResult;
    @Mock private TransactionProcessingResult processingResult;

    private MembershipContractClient client;

    @BeforeEach
    void setUp() {
        client = new MembershipContractClient(simulator, CONTRACT);
        // lenient: no todos los tests llegan a llamar simulate (los de fallo temprano no).
        lenient().when(simulator.simulatePendingBlockHeader()).thenReturn(header);
    }

    /** Helper: cablear el mock para devolver un Optional<TransactionSimulationResult> exitoso con el output dado. */
    private void simulationReturns(final Bytes output) {
        when(simulationResult.isInvalid()).thenReturn(false);
        when(simulationResult.isSuccessful()).thenReturn(true);
        when(simulationResult.result()).thenReturn(processingResult);
        when(processingResult.getOutput()).thenReturn(output);
        when(simulator.simulate(
                        any(CallParameter.class),
                        any(),
                        any(ProcessableBlockHeader.class),
                        any(OperationTracer.class),
                        any(EnumSet.class)))
                .thenReturn(Optional.of(simulationResult));
    }

    /** Construye un retorno ABI-encoded de un uint8: 32 bytes con el valor en el último byte. */
    private static Bytes uint8Word(final int value) {
        byte[] word = new byte[32];
        word[31] = (byte) value;
        return Bytes.wrap(word);
    }

    @Test
    void decodifica_NONE_BASIC_STANDARD_PREMIUM_WHITELISTED() {
        for (Tier expected : Tier.values()) {
            // reset mocks suaves entre iteraciones
            simulationReturns(uint8Word(expected.onChainValue()));
            assertEquals(expected, client.getTier(ACCOUNT),
                    "Tier " + expected + " (on-chain=" + expected.onChainValue() + ") debe decodificar correctamente");
        }
    }

    @Test
    void devuelveNoneCuandoSimulacionEsOptionalEmpty() {
        when(simulator.simulate(
                        any(CallParameter.class), any(), any(ProcessableBlockHeader.class),
                        any(OperationTracer.class), any(EnumSet.class)))
                .thenReturn(Optional.empty());

        assertEquals(Tier.NONE, client.getTier(ACCOUNT));
    }

    @Test
    void devuelveNoneCuandoSimulacionEsInvalid() {
        when(simulationResult.isInvalid()).thenReturn(true);
        when(simulator.simulate(
                        any(CallParameter.class), any(), any(ProcessableBlockHeader.class),
                        any(OperationTracer.class), any(EnumSet.class)))
                .thenReturn(Optional.of(simulationResult));

        assertEquals(Tier.NONE, client.getTier(ACCOUNT));
        // Si fue invalid, nunca debería haber pedido el output.
        verify(processingResult, never()).getOutput();
    }

    @Test
    void devuelveNoneCuandoSimulacionNoExitosa() {
        when(simulationResult.isInvalid()).thenReturn(false);
        when(simulationResult.isSuccessful()).thenReturn(false);
        when(simulator.simulate(
                        any(CallParameter.class), any(), any(ProcessableBlockHeader.class),
                        any(OperationTracer.class), any(EnumSet.class)))
                .thenReturn(Optional.of(simulationResult));

        assertEquals(Tier.NONE, client.getTier(ACCOUNT));
    }

    @Test
    void devuelveNoneCuandoOutputEsVacio() {
        simulationReturns(Bytes.EMPTY);
        assertEquals(Tier.NONE, client.getTier(ACCOUNT));
    }

    @Test
    void devuelveNoneCuandoTierOnChainEstaFueraDeRango() {
        simulationReturns(uint8Word(99));
        assertEquals(Tier.NONE, client.getTier(ACCOUNT));
    }

    @Test
    void devuelveNoneCuandoSimulatorLanza() {
        when(simulator.simulate(
                        any(CallParameter.class), any(), any(ProcessableBlockHeader.class),
                        any(OperationTracer.class), any(EnumSet.class)))
                .thenThrow(new RuntimeException("nodo caído"));

        assertEquals(Tier.NONE, client.getTier(ACCOUNT));
    }

    @Test
    void abiEncodingTieneSelectorYAddressPadded() {
        simulationReturns(uint8Word(Tier.BASIC.onChainValue()));
        client.getTier(ACCOUNT);

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
        // 4 bytes selector + 32 bytes address padded
        assertEquals(36, payload.size(), "calldata debe ser 4 + 32 bytes");
        assertEquals("0xb45aae52", payload.slice(0, 4).toHexString(),
                "primeros 4 bytes deben ser el selector de getTier(address)");
        // Los primeros 12 bytes del address-word deben ser ceros (left-pad)
        assertTrue(payload.slice(4, 12).isZero(), "address debe estar left-padded con ceros");
        // Los últimos 20 bytes del address-word son el address real
        assertEquals(ACCOUNT.toUnprefixedHexString(),
                payload.slice(16, 20).toUnprefixedHexString());
    }
}
