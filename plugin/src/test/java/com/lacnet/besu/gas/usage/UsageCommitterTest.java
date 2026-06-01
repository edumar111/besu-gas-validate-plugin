package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class UsageCommitterTest {

    private static final Address METER = Address.fromHexString("0x2222222222222222222222222222222222222222");
    private static final Address RECORDER = Address.fromHexString("0x4ec04ec04ec04ec04ec04ec04ec04ec04ec04ec0");
    private static final Address ACC = Address.fromHexString("0x00000000000000000000000000000000000000b0");
    private static final long PERIOD = 2026L * 12 + 4;
    private static final long CHAIN_ID = 650540L;

    /** Firma fake determinística. */
    private static final RecorderSigner FAKE_SIGNER = new RecorderSigner() {
        @Override public Address address() { return RECORDER; }
        @Override public Signature sign(Bytes32 hash) {
            return new Signature(BigInteger.valueOf(111), BigInteger.valueOf(222), 0);
        }
    };

    /** Keccak fake (no necesitamos crypto real para testear la orquestación). */
    private static final java.util.function.Function<Bytes, Bytes32> FAKE_KECCAK =
            b -> Bytes32.leftPad(Bytes.ofUnsignedLong(b.size()));

    /** RPC fake que registra lo enviado y devuelve nonce fijo. */
    private static final class FakeRpc implements RecorderRpc {
        long nonce = 7L;
        final List<Bytes> sent = new ArrayList<>();
        boolean fail = false;
        @Override public long pendingNonce(Address account) { return nonce; }
        @Override public String sendRawTransaction(Bytes signedRawTx) {
            if (fail) {
                throw new RuntimeException("nodo caído");
            }
            sent.add(signedRawTx);
            return "0xdeadbeef";
        }
    }

    private UsageCommitter committer(MonthlyUsageTracker tracker, FakeRpc rpc) {
        return new UsageCommitter(
                tracker, METER, CHAIN_ID, 8_000_000L, FAKE_SIGNER, rpc, FAKE_KECCAK);
    }

    @Test
    void noPendingDoesNotSend() {
        MonthlyUsageTracker tracker = new MonthlyUsageTracker(PERIOD);
        FakeRpc rpc = new FakeRpc();
        committer(tracker, rpc).onCommitDue(150);
        assertTrue(rpc.sent.isEmpty());
    }

    @Test
    void sendsAndAppliesCommittedOnSuccess() {
        MonthlyUsageTracker tracker = new MonthlyUsageTracker(PERIOD);
        tracker.addUsage(ACC, 1000L, (p, a) -> 0L);
        FakeRpc rpc = new FakeRpc();
        committer(tracker, rpc).onCommitDue(150);

        assertEquals(1, rpc.sent.size());
        // tras éxito, el pending se mueve a baseline → snapshotPending vacío, cumulative conservado
        assertTrue(tracker.snapshotPending().isEmpty());
        assertEquals(1000L, tracker.cumulative(ACC, (p, a) -> 0L));
    }

    @Test
    void preservesPendingOnFailure() {
        MonthlyUsageTracker tracker = new MonthlyUsageTracker(PERIOD);
        tracker.addUsage(ACC, 1000L, (p, a) -> 0L);
        FakeRpc rpc = new FakeRpc();
        rpc.fail = true;
        committer(tracker, rpc).onCommitDue(150);

        assertTrue(rpc.sent.isEmpty());
        // falla → el delta queda pendiente para reintento
        assertEquals(1000L, tracker.snapshotPending().get(ACC));
    }

    @Test
    void sentTxIsValidRlpListWithSelector() {
        MonthlyUsageTracker tracker = new MonthlyUsageTracker(PERIOD);
        tracker.addUsage(ACC, 1234L, (p, a) -> 0L);
        FakeRpc rpc = new FakeRpc();
        committer(tracker, rpc).onCommitDue(150);

        Bytes tx = rpc.sent.get(0);
        // RLP list
        assertTrue((tx.get(0) & 0xFF) >= 0xc0);
        // el selector recordUsageBatch debe aparecer en el data
        assertTrue(tx.toHexString().contains("1e5092f1"));
    }
}
