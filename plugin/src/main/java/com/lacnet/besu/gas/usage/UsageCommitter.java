package com.lacnet.besu.gas.usage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Commitea el uso de gas acumulado al {@code UsageMeter} on-chain (Fase 2). Solo corre en el nodo
 * recorder: lo dispara el {@link UsageBlockListener} como {@link CommitTrigger} cada
 * {@code commitEveryBlocks} bloques.
 *
 * <p>Flujo por commit:
 * <ol>
 *   <li>Snapshot de los {@code pendingDelta} del tracker. Si está vacío, no hace nada.</li>
 *   <li>Arma la calldata {@code recordUsageBatch(periodId, accounts, deltas)}.</li>
 *   <li>Pide el nonce pendiente, construye y firma la TX legacy EIP-155.</li>
 *   <li>La envía vía {@code eth_sendRawTransaction}. Si tiene éxito, mueve los deltas del snapshot
 *       de {@code pending} a {@code baseline} ({@code applyCommitted}); si falla, los deja pendientes
 *       para el próximo intento.</li>
 * </ol>
 *
 * <p>El recorder debe estar {@code WHITELISTED} en el {@code MembershipRegistry} (si no, este mismo
 * plugin rechazaría sus TX). Como la red es zero-gas, {@code gasPrice = 0} y {@code value = 0}.
 */
public final class UsageCommitter implements CommitTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(UsageCommitter.class);

    private final MonthlyUsageTracker tracker;
    private final Address meterContract;
    private final long chainId;
    private final long gasLimit;
    private final RecorderSigner signer;
    private final RecorderRpc rpc;
    private final Function<Bytes, Bytes32> keccak;

    public UsageCommitter(
            final MonthlyUsageTracker tracker,
            final Address meterContract,
            final long chainId,
            final long gasLimit,
            final RecorderSigner signer,
            final RecorderRpc rpc,
            final Function<Bytes, Bytes32> keccak) {
        this.tracker = tracker;
        this.meterContract = meterContract;
        this.chainId = chainId;
        this.gasLimit = gasLimit;
        this.signer = signer;
        this.rpc = rpc;
        this.keccak = keccak;
    }

    @Override
    public void onCommitDue(final long blockNumber) {
        final Map<Address, Long> pending = tracker.snapshotPending();
        if (pending.isEmpty()) {
            LOG.debug("commit en bloque {}: nada pendiente", blockNumber);
            return;
        }

        final long periodId = tracker.currentPeriodId();
        final List<Address> accounts = new ArrayList<>(pending.keySet());
        final List<Long> deltas = new ArrayList<>(accounts.size());
        for (Address a : accounts) {
            deltas.add(pending.get(a));
        }

        try {
            final Bytes data = RecordUsageTxEncoder.encodeCallData(periodId, accounts, deltas);
            final long nonce = rpc.pendingNonce(signer.address());
            final Bytes unsigned = RecordUsageTxEncoder.unsignedTx(
                    nonce, BigInteger.ZERO, gasLimit, meterContract, BigInteger.ZERO, data, chainId);
            final Bytes32 hash = keccak.apply(unsigned);
            final RecorderSigner.Signature sig = signer.sign(hash);
            final Bytes signed = RecordUsageTxEncoder.signedTx(
                    nonce, BigInteger.ZERO, gasLimit, meterContract, BigInteger.ZERO, data, chainId,
                    sig.recId(), sig.r(), sig.s());

            final String txHash = rpc.sendRawTransaction(signed);

            // Éxito: el delta enviado pasa a baseline (no se re-commiteará).
            tracker.applyCommitted(pending);
            LOG.info("commit en bloque {}: recordUsageBatch enviado (period={} cuentas={} nonce={} tx={})",
                    blockNumber, periodId, accounts.size(), nonce, txHash);
        } catch (RuntimeException e) {
            // Falla: NO aplicamos el commit; los deltas quedan pendientes para el próximo intento.
            LOG.warn("commit en bloque {} falló ({} cuentas); se reintenta en el próximo ciclo",
                    blockNumber, accounts.size(), e);
        }
    }
}
