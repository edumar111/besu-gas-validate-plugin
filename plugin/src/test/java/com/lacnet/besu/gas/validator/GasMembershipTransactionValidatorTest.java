package com.lacnet.besu.gas.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.events.RejectionEvent;
import com.lacnet.besu.gas.events.RejectionEventBus;
import com.lacnet.besu.gas.model.Tier;
import com.lacnet.besu.gas.selector.GasMembershipTransactionSelector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.services.TransactionSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GasMembershipTransactionValidatorTest {

    private static final String CONTRACT_ADDR = "0x1234567890123456789012345678901234567890";
    private static final long PENDING_BLOCK = 101L;

    private static final Address ALICE = Address.fromHexString("0x" + "a".repeat(40));

    @Mock private MembershipContractClient client;
    @Mock private TransactionSimulationService simulator;
    @Mock private ProcessableBlockHeader pendingHeader;

    private GasMembershipConfig config;
    private TierCache cache;
    private RejectionEventBus eventBus;
    private GasMembershipTransactionValidator validator;

    @BeforeEach
    void setUp() {
        config = GasMembershipConfig.fromProperties(Map.of(
                GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT, CONTRACT_ADDR)::get);
        cache = new TierCache(config.getTierCacheTtlBlocks());
        eventBus = new RejectionEventBus();
        validator = new GasMembershipTransactionValidator(config, client, cache, simulator, eventBus);

        lenient().when(pendingHeader.getNumber()).thenReturn(PENDING_BLOCK);
        lenient().when(simulator.simulatePendingBlockHeader()).thenReturn(pendingHeader);
    }

    private Transaction tx(final Address sender, final long gasLimit) {
        Transaction tx = org.mockito.Mockito.mock(Transaction.class);
        lenient().when(tx.getSender()).thenReturn(sender);
        lenient().when(tx.getGasLimit()).thenReturn(gasLimit);
        lenient().when(tx.getHash()).thenReturn(Hash.fromHexString("0x" + "22".repeat(32)));
        return tx;
    }

    private void givenTier(final Address account, final Tier tier) {
        when(client.getTier(account)).thenReturn(tier);
    }

    @Test
    void whitelistedEsValida() {
        givenTier(ALICE, Tier.WHITELISTED);
        Optional<String> result = validator.validateTransaction(tx(ALICE, 50_000_000L), true, false);
        assertTrue(result.isEmpty(), "WHITELISTED debe pasar siempre");
    }

    @Test
    void premiumEsValidaSiempreUnEvaluacionPerBlockLaHaceElSelector() {
        givenTier(ALICE, Tier.PREMIUM);
        // Aunque pida más que su quota (10M), el validator deja pasar — el selector
        // decide con el sobrante real del bloque.
        Optional<String> result = validator.validateTransaction(tx(ALICE, 50_000_000L), true, false);
        assertTrue(result.isEmpty(), "PREMIUM siempre pasa el validator");
    }

    @Test
    void noneEsInvalidaConRazonNoMembership() {
        givenTier(ALICE, Tier.NONE);
        Optional<String> result = validator.validateTransaction(tx(ALICE, 21_000L), true, false);
        assertEquals(GasMembershipTransactionSelector.REASON_NO_MEMBERSHIP,
                result.orElseThrow(),
                "NONE debe ser rechazada con razón no-membership");
    }

    @Test
    void basicTxGasLimitMayorQueQuotaEsInvalidaConRazonExcedeTierQuota() {
        givenTier(ALICE, Tier.BASIC);
        // BASIC=500K; mando 500K+1 → la TX no cabe en NINGÚN bloque futuro.
        Optional<String> result = validator.validateTransaction(tx(ALICE, 500_001L), true, false);
        assertEquals(GasMembershipTransactionSelector.REASON_TX_EXCEEDS_TIER_QUOTA,
                result.orElseThrow(),
                "txGasLimit > quota debe ser rechazada upfront");
    }

    @Test
    void basicTxGasLimitMenorOIgualQueQuotaEsValidaSelectorDecide() {
        givenTier(ALICE, Tier.BASIC);
        // BASIC=500K; mando exactamente 500K → cabe en un bloque vacío.
        // El validator deja pasar; el selector decide con el accounting per-block.
        Optional<String> result = validator.validateTransaction(tx(ALICE, 500_000L), true, false);
        assertTrue(result.isEmpty(),
                "BASIC con txGasLimit ≤ quota debe pasar al selector");
    }

    @Test
    void standardTxGasLimitMayorQueQuotaEsInvalida() {
        givenTier(ALICE, Tier.STANDARD);
        // STANDARD=5M; mando 5M+1 → rechazo permanente.
        Optional<String> result = validator.validateTransaction(tx(ALICE, 5_000_001L), true, false);
        assertEquals(GasMembershipTransactionSelector.REASON_TX_EXCEEDS_TIER_QUOTA,
                result.orElseThrow());
    }

    @Test
    void cacheReutilizaResultadoEnMismoBloque() {
        givenTier(ALICE, Tier.BASIC);

        // Tres validaciones consecutivas del mismo sender en el mismo bloque pendiente.
        for (int i = 0; i < 3; i++) {
            validator.validateTransaction(tx(ALICE, 100_000L), true, false);
        }

        // El client.getTier debe haberse invocado UNA SOLA vez — el resto fue cache hit.
        verify(client, times(1)).getTier(ALICE);
    }

    // === Emisión al RejectionEventBus (Fase 1.6) ===

    @Test
    void rechazoEmiteEventoConSourceValidator() {
        givenTier(ALICE, Tier.BASIC);
        validator.validateTransaction(tx(ALICE, 500_001L), true, false);

        List<RejectionEvent> events = eventBus.listBySender(ALICE, 10);
        assertEquals(1, events.size());
        RejectionEvent ev = events.get(0);
        assertEquals(GasMembershipTransactionSelector.REASON_TX_EXCEEDS_TIER_QUOTA, ev.reason());
        assertEquals(RejectionEvent.Source.VALIDATOR, ev.source());
        assertEquals(0L, ev.blockNumber(), "el validator no tiene block context");
    }

    @Test
    void txValidaNoEmiteEvento() {
        givenTier(ALICE, Tier.BASIC);
        validator.validateTransaction(tx(ALICE, 400_000L), true, false);
        assertTrue(eventBus.listBySender(ALICE, 10).isEmpty(),
                "una TX que pasa el validator no debe emitir rechazo");
    }
}
