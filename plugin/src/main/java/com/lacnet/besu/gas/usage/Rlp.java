package com.lacnet.besu.gas.usage;

import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;

/**
 * Encoder RLP mínimo (Recursive Length Prefix), suficiente para construir una transacción legacy
 * EIP-155 firmada por el nodo recorder (Fase 2). Implementado a mano para no bundlear web3j: las
 * únicas estructuras que necesitamos son strings de bytes y una lista.
 *
 * <p>Reglas (ver Ethereum Yellow Paper, Appendix B):
 * <ul>
 *   <li>Un único byte en {@code [0x00, 0x7f]} se codifica como sí mismo.</li>
 *   <li>Un string de 0–55 bytes: {@code 0x80 + len} seguido del string.</li>
 *   <li>Un string de &gt;55 bytes: {@code 0xb7 + len(len)}, {@code len} big-endian, string.</li>
 *   <li>Una lista cuyo payload mide 0–55 bytes: {@code 0xc0 + len} seguido del payload.</li>
 *   <li>Una lista cuyo payload mide &gt;55 bytes: {@code 0xf7 + len(len)}, {@code len}, payload.</li>
 * </ul>
 */
public final class Rlp {

    private Rlp() {}

    /** Codifica un string de bytes. Los ceros a la izquierda NO se recortan (el caller decide). */
    public static Bytes encodeBytes(final Bytes value) {
        if (value.size() == 1 && (value.get(0) & 0xFF) <= 0x7f) {
            return value;
        }
        return Bytes.concatenate(encodeLength(value.size(), 0x80), value);
    }

    /**
     * Codifica un entero sin signo como un string de bytes big-endian sin ceros a la izquierda.
     * El cero se codifica como string vacío ({@code 0x80}), como exige RLP para escalares.
     */
    public static Bytes encodeLong(final long value) {
        if (value < 0) {
            throw new IllegalArgumentException("RLP scalar no puede ser negativo: " + value);
        }
        if (value == 0) {
            return encodeBytes(Bytes.EMPTY);
        }
        return encodeBytes(Bytes.minimalBytes(value));
    }

    /** Codifica un {@link BigInteger} sin signo como string de bytes big-endian sin ceros líderes. */
    public static Bytes encodeBigInteger(final BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("RLP scalar no puede ser negativo: " + value);
        }
        if (value.signum() == 0) {
            return encodeBytes(Bytes.EMPTY);
        }
        return encodeBytes(Bytes.wrap(trimLeadingZeros(value.toByteArray())));
    }

    /** Codifica una lista de elementos ya RLP-codificados. */
    public static Bytes encodeList(final List<Bytes> encodedItems) {
        Bytes payload = Bytes.concatenate(encodedItems.toArray(new Bytes[0]));
        return Bytes.concatenate(encodeLength(payload.size(), 0xc0), payload);
    }

    private static Bytes encodeLength(final int length, final int offset) {
        if (length <= 55) {
            return Bytes.of((byte) (offset + length));
        }
        Bytes lenBytes = Bytes.minimalBytes(length);
        byte prefix = (byte) (offset + 55 + lenBytes.size());
        return Bytes.concatenate(Bytes.of(prefix), lenBytes);
    }

    private static byte[] trimLeadingZeros(final byte[] in) {
        int i = 0;
        while (i < in.length - 1 && in[i] == 0) {
            i++;
        }
        if (i == 0) {
            return in;
        }
        byte[] out = new byte[in.length - i];
        System.arraycopy(in, i, out, 0, out.length);
        return out;
    }
}
