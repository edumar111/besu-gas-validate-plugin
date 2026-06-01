package com.lacnet.besu.gas.usage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;

/**
 * Construye la calldata y la transacción legacy EIP-155 para
 * {@code UsageMeter.recordUsageBatch(uint256 periodId, address[] accounts, uint256[] gasDeltas)}
 * que el nodo recorder firma y envía (Fase 2).
 *
 * <p>Todo acá es <b>puro</b> (sin IO, sin crypto): la calldata ABI y el RLP de la TX se pueden
 * testear contra vectores conocidos. La firma (keccak + secp256k1) vive en el adaptador
 * {@code Secp256k1RecorderSigner}, y el envío en {@code JsonRpcRecorderRpc}.
 */
public final class RecordUsageTxEncoder {

    /** Selector de {@code recordUsageBatch(uint256,address[],uint256[])} (cross-stack con Solidity). */
    public static final Bytes RECORD_USAGE_BATCH_SELECTOR = Bytes.fromHexString("0x1e5092f1");

    private RecordUsageTxEncoder() {}

    /**
     * Calldata ABI-encoded para {@code recordUsageBatch}.
     *
     * @param periodId período (mes calendario UTC)
     * @param accounts cuentas (mismo orden que {@code deltas})
     * @param deltas delta de gas no commiteado por cuenta (≥ 0)
     * @return selector + argumentos ABI
     */
    public static Bytes encodeCallData(
            final long periodId, final List<Address> accounts, final List<Long> deltas) {
        if (accounts.size() != deltas.size()) {
            throw new IllegalArgumentException("accounts y deltas deben tener el mismo tamaño");
        }
        final int n = accounts.size();
        // Layout ABI de (uint256, address[], uint256[]):
        //   [0]  periodId
        //   [1]  offset a accounts  = 0x60 (3 palabras de head)
        //   [2]  offset a deltas    = 0x60 + 32 + 32*n
        //   accounts: len(n) + n palabras
        //   deltas:   len(n) + n palabras
        final long offsetAccounts = 0x60L;
        final long offsetDeltas = 0x60L + 32L + 32L * n;

        List<Bytes> words = new ArrayList<>();
        words.add(word(periodId));
        words.add(word(offsetAccounts));
        words.add(word(offsetDeltas));
        // accounts
        words.add(word(n));
        for (Address a : accounts) {
            words.add(Bytes32.leftPad(a));
        }
        // deltas
        words.add(word(n));
        for (Long d : deltas) {
            if (d == null || d < 0) {
                throw new IllegalArgumentException("delta inválido: " + d);
            }
            words.add(word(d));
        }
        return Bytes.concatenate(
                RECORD_USAGE_BATCH_SELECTOR,
                Bytes.concatenate(words.toArray(new Bytes[0])));
    }

    /**
     * RLP de la TX legacy <b>sin firmar</b> para el hash de firma EIP-155:
     * {@code RLP[nonce, gasPrice, gasLimit, to, value, data, chainId, 0, 0]}.
     */
    public static Bytes unsignedTx(
            final long nonce,
            final BigInteger gasPrice,
            final long gasLimit,
            final Address to,
            final BigInteger value,
            final Bytes data,
            final long chainId) {
        List<Bytes> fields = new ArrayList<>();
        fields.add(Rlp.encodeLong(nonce));
        fields.add(Rlp.encodeBigInteger(gasPrice));
        fields.add(Rlp.encodeLong(gasLimit));
        fields.add(Rlp.encodeBytes(to));
        fields.add(Rlp.encodeBigInteger(value));
        fields.add(Rlp.encodeBytes(data));
        fields.add(Rlp.encodeLong(chainId));
        fields.add(Rlp.encodeLong(0L));
        fields.add(Rlp.encodeLong(0L));
        return Rlp.encodeList(fields);
    }

    /**
     * RLP de la TX legacy <b>firmada</b>:
     * {@code RLP[nonce, gasPrice, gasLimit, to, value, data, v, r, s]} con
     * {@code v = recId + 35 + 2*chainId} (EIP-155).
     */
    public static Bytes signedTx(
            final long nonce,
            final BigInteger gasPrice,
            final long gasLimit,
            final Address to,
            final BigInteger value,
            final Bytes data,
            final long chainId,
            final int recId,
            final BigInteger r,
            final BigInteger s) {
        final long v = (long) recId + 35L + 2L * chainId;
        List<Bytes> fields = new ArrayList<>();
        fields.add(Rlp.encodeLong(nonce));
        fields.add(Rlp.encodeBigInteger(gasPrice));
        fields.add(Rlp.encodeLong(gasLimit));
        fields.add(Rlp.encodeBytes(to));
        fields.add(Rlp.encodeBigInteger(value));
        fields.add(Rlp.encodeBytes(data));
        fields.add(Rlp.encodeLong(v));
        fields.add(Rlp.encodeBigInteger(r));
        fields.add(Rlp.encodeBigInteger(s));
        return Rlp.encodeList(fields);
    }

    private static Bytes word(final long value) {
        return Bytes32.leftPad(Bytes.minimalBytes(value));
    }
}
