package com.lacnet.besu.gas.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.lacnet.besu.gas.cache.TierCache;
import com.lacnet.besu.gas.client.MembershipContractClient;
import com.lacnet.besu.gas.config.GasMembershipConfig;
import com.lacnet.besu.gas.model.Tier;
import com.lacnet.besu.gas.tracker.BlockGasTracker;
import java.util.HashMap;
import java.util.Map;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.PendingTransaction;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.data.TransactionProcessingResult;
import org.hyperledger.besu.plugin.data.TransactionSelectionResult;
import org.hyperledger.besu.plugin.services.txselection.TransactionEvaluationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GasMembershipTransactionSelectorTest {

    private static final String CONTRACT_ADDR = "0x1234567890123456789012345678901234567890";
    private static final long BLOCK_GAS_LIMIT = 350_000_000L;
    private static final long BLOCK_NUMBER = 100L;

    private static final Address ALICE = Address.fromHexString("0x" + "a".repeat(40));
    private static final Address BOB = Address.fromHexString("0x" + "b".repeat(40));

    @Mock private MembershipContractClient client;

    private GasMembershipConfig config;
    private TierCache cache;
    private BlockGasTracker tracker;
    private GasMembershipTransactionSelector selector;

    @BeforeEach
    void setUp() {
        // Defaults reales: BASIC=500K, STANDARD=5M, PREMIUM=10M, TTL=50.
        config = GasMembershipConfig.fromProperties(Map.of(
                GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT, CONTRACT_ADDR)::get);
        cache = new TierCache(config.getTierCacheTtlBlocks());
        tracker = new BlockGasTracker();
        selector = new GasMembershipTransactionSelector(config, client, cache, tracker);
    }

    /** Builder mínimo para un {@link TransactionEvaluationContext}. */
    private TransactionEvaluationContext context(
            final long blockNumber, final long blockGasLimit, final Address sender, final long txGasLimit) {
        ProcessableBlockHeader header = org.mockito.Mockito.mock(ProcessableBlockHeader.class);
        Transaction tx = org.mockito.Mockito.mock(Transaction.class);
        PendingTransaction ptx = org.mockito.Mockito.mock(PendingTransaction.class);
        TransactionEvaluationContext ctx = org.mockito.Mockito.mock(TransactionEvaluationContext.class);

        // lenient: no todos los tests llaman a todos los getters.
        lenient().when(header.getNumber()).thenReturn(blockNumber);
        lenient().when(header.getGasLimit()).thenReturn(blockGasLimit);
        lenient().when(tx.getSender()).thenReturn(sender);
        lenient().when(tx.getGasLimit()).thenReturn(txGasLimit);
        lenient().when(ptx.getTransaction()).thenReturn(tx);
        when(ctx.getPendingBlockHeader()).thenReturn(header);
        when(ctx.getPendingTransaction()).thenReturn(ptx);
        return ctx;
    }

    private void givenTier(final Address account, final Tier tier) {
        when(client.getTier(account)).thenReturn(tier);
    }

    @Test
    void whitelistedSiempreEsSelectedAunqueExcedaCualquierCuota() {
        givenTier(ALICE, Tier.WHITELISTED);
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, BLOCK_GAS_LIMIT));
        assertEquals(TransactionSelectionResult.SELECTED, result);
    }

    @Test
    void noneEsInvalidPermanenteConRazonNoMembership() {
        givenTier(ALICE, Tier.NONE);
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 21_000L));
        assertEquals(GasMembershipTransactionSelector.REASON_NO_MEMBERSHIP,
                result.maybeInvalidReason().orElseThrow());
        assertFalse(result.selected());
        // invalid() es descarte permanente — la TX no debe quedarse en el pool.
        assertTrue(result.discard(), "NONE debería descartar la TX del pool");
    }

    @Test
    void basicDentroDeQuotaEsSelected() {
        givenTier(ALICE, Tier.BASIC);
        // BASIC tiene 500K; mando una TX de 400K.
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 400_000L));
        assertEquals(TransactionSelectionResult.SELECTED, result);
    }

    @Test
    void basicTxGasLimitMayorQueQuotaEsInvalidPermanente() {
        givenTier(ALICE, Tier.BASIC);
        // BASIC=500K; pedimos 500K + 1 byte → txGasLimit > quota, no cabe en NINGÚN bloque.
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 500_001L));
        assertEquals(GasMembershipTransactionSelector.REASON_TX_EXCEEDS_TIER_QUOTA,
                result.maybeInvalidReason().orElseThrow());
        assertFalse(result.selected());
        // invalid() es descarte permanente — la TX no debe quedarse en el pool reintentando.
        assertTrue(result.discard(), "txGasLimit > quota debe ser descarte permanente");
    }

    @Test
    void basicTxGasLimitMenorQueQuotaPeroBloqueAgotadoEsInvalidTransient() {
        givenTier(ALICE, Tier.BASIC);
        // ALICE agota su cuota (500K) en este bloque.
        selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 500_000L));
        tracker.add(ALICE, 500_000L);

        // Segunda TX de 1 gas: txGasLimit (1) ≤ quota (500K) PERO used+txGasLimit > quota.
        // En el siguiente bloque sí va a caber → invalidTransient (reintentar).
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 1L));
        assertEquals(GasMembershipTransactionSelector.REASON_BLOCK_QUOTA_EXCEEDED,
                result.maybeInvalidReason().orElseThrow());
        assertFalse(result.selected());
        assertFalse(result.discard(), "exceso per-block (no per-tx) NO debería descartar");
    }

    @Test
    void standardEnBoundaryExactoEsSelected() {
        givenTier(ALICE, Tier.STANDARD);
        // STANDARD=5M; uso 5M exacto → cabe.
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 5_000_000L));
        assertEquals(TransactionSelectionResult.SELECTED, result);
    }

    @Test
    void premiumDentroDeQuotaEsSelected() {
        givenTier(ALICE, Tier.PREMIUM);
        // PREMIUM=10M; uso 10M exacto.
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 10_000_000L));
        assertEquals(TransactionSelectionResult.SELECTED, result);
    }

    @Test
    void premiumExcedeQuotaPeroCabeEnSobrante() {
        givenTier(ALICE, Tier.PREMIUM);
        // Bloque vacío → sobrante = 350M. PREMIUM puede pedir hasta 350M.
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 50_000_000L));
        assertEquals(TransactionSelectionResult.SELECTED, result);
    }

    @Test
    void premiumExcedeQuotaYTambienElSobrante() {
        givenTier(ALICE, Tier.PREMIUM);
        givenTier(BOB, Tier.PREMIUM);

        // Llenamos el bloque casi entero con BOB.
        // Primero, BOB pide 340M (cabe en el sobrante).
        selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, BOB, 340_000_000L));
        // Simulamos que se procesó.
        tracker.add(BOB, 340_000_000L);

        // Ahora ALICE pide 11M → excede su quota (10M) y excede el sobrante (350M-340M=10M).
        TransactionSelectionResult result = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 11_000_000L));
        assertEquals(GasMembershipTransactionSelector.REASON_BLOCK_QUOTA_EXCEEDED,
                result.maybeInvalidReason().orElseThrow());
    }

    @Test
    void cambioDeBloqueResetaContadores() {
        givenTier(ALICE, Tier.BASIC);

        // Bloque 100: ALICE consume su quota (500K). Una segunda TX no entra.
        selector.evaluateTransactionPreProcessing(
                context(100L, BLOCK_GAS_LIMIT, ALICE, 500_000L));
        tracker.add(ALICE, 500_000L);

        TransactionSelectionResult denied = selector.evaluateTransactionPreProcessing(
                context(100L, BLOCK_GAS_LIMIT, ALICE, 1L));
        assertEquals(GasMembershipTransactionSelector.REASON_BLOCK_QUOTA_EXCEEDED,
                denied.maybeInvalidReason().orElseThrow());

        // Bloque 101: el tracker se resetea → ALICE puede consumir su quota completa otra vez.
        TransactionSelectionResult fresh = selector.evaluateTransactionPreProcessing(
                context(101L, BLOCK_GAS_LIMIT, ALICE, 500_000L));
        assertEquals(TransactionSelectionResult.SELECTED, fresh);
    }

    @Test
    void postProcessingAcumulaGasUsadoReal() {
        givenTier(ALICE, Tier.BASIC);

        // Primera TX: pre-proc OK (400K cabe en 500K).
        TransactionEvaluationContext ctx1 = context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 400_000L);
        assertEquals(TransactionSelectionResult.SELECTED,
                selector.evaluateTransactionPreProcessing(ctx1));

        // Post-processing con gasUsed real de 350K (menos que el gasLimit estimado).
        TransactionProcessingResult result1 = org.mockito.Mockito.mock(TransactionProcessingResult.class);
        when(result1.getEstimateGasUsedByTransaction()).thenReturn(350_000L);
        selector.evaluateTransactionPostProcessing(ctx1, result1);
        assertEquals(350_000L, tracker.getUsed(ALICE));

        // Segunda TX: 200K. Acumulado proyectado = 350K + 200K = 550K > 500K → rechazo.
        TransactionSelectionResult denied = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 200_000L));
        assertEquals(GasMembershipTransactionSelector.REASON_BLOCK_QUOTA_EXCEEDED,
                denied.maybeInvalidReason().orElseThrow());

        // Tercera TX: 150K. Acumulado proyectado = 350K + 150K = 500K (boundary OK).
        TransactionSelectionResult ok = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 150_000L));
        assertEquals(TransactionSelectionResult.SELECTED, ok);
    }

    @Test
    void cacheDelTierEvitaLecturasRepetidas() {
        givenTier(ALICE, Tier.BASIC);

        // 5 invocaciones consecutivas para el mismo sender en el mismo bloque.
        for (int i = 0; i < 5; i++) {
            selector.evaluateTransactionPreProcessing(
                    context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 1L));
        }

        // El client debe haberse consultado exactamente UNA vez (resto fue cache hit).
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).getTier(ALICE);
    }

    @Test
    void variosSendersAcumulanIndependientemente() {
        Map<Address, Tier> tiers = new HashMap<>();
        tiers.put(ALICE, Tier.BASIC);
        tiers.put(BOB, Tier.STANDARD);
        when(client.getTier(org.mockito.ArgumentMatchers.any(Address.class)))
                .thenAnswer(inv -> tiers.get((Address) inv.getArgument(0)));

        // ALICE consume su quota (500K). BOB consume parte de la suya (3M).
        TransactionEvaluationContext aliceCtx = context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 500_000L);
        assertEquals(TransactionSelectionResult.SELECTED,
                selector.evaluateTransactionPreProcessing(aliceCtx));
        tracker.add(ALICE, 500_000L);

        TransactionEvaluationContext bobCtx = context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, BOB, 3_000_000L);
        assertEquals(TransactionSelectionResult.SELECTED,
                selector.evaluateTransactionPreProcessing(bobCtx));
        tracker.add(BOB, 3_000_000L);

        // ALICE ya no puede más; BOB todavía sí.
        TransactionSelectionResult aliceDenied = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, ALICE, 1L));
        assertEquals(GasMembershipTransactionSelector.REASON_BLOCK_QUOTA_EXCEEDED,
                aliceDenied.maybeInvalidReason().orElseThrow());

        TransactionSelectionResult bobOk = selector.evaluateTransactionPreProcessing(
                context(BLOCK_NUMBER, BLOCK_GAS_LIMIT, BOB, 1_000_000L));
        assertEquals(TransactionSelectionResult.SELECTED, bobOk);
    }
}
