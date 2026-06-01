package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class RlpTest {

    // Vectores del Ethereum Yellow Paper / ethereumjs.

    @Test
    void singleLowByteIsItself() {
        assertEquals("0x00", Rlp.encodeBytes(Bytes.fromHexString("0x00")).toHexString());
        assertEquals("0x7f", Rlp.encodeBytes(Bytes.fromHexString("0x7f")).toHexString());
    }

    @Test
    void singleHighByteGetsPrefix() {
        // 0x80 → string de 1 byte: 0x81 0x80
        assertEquals("0x8180", Rlp.encodeBytes(Bytes.fromHexString("0x80")).toHexString());
    }

    @Test
    void emptyStringIs0x80() {
        assertEquals("0x80", Rlp.encodeBytes(Bytes.EMPTY).toHexString());
    }

    @Test
    void shortStringPrefix() {
        // "dog" = 0x646f67 → 0x83 646f67
        assertEquals("0x83646f67",
                Rlp.encodeBytes(Bytes.wrap("dog".getBytes())).toHexString());
    }

    @Test
    void longStringPrefix() {
        // 56 bytes de 0x61 ("a") → 0xb838 + 56 bytes
        byte[] data = new byte[56];
        java.util.Arrays.fill(data, (byte) 0x61);
        Bytes encoded = Rlp.encodeBytes(Bytes.wrap(data));
        assertEquals("0xb838", encoded.slice(0, 2).toHexString());
        assertEquals(58, encoded.size());
    }

    @Test
    void scalarZeroIsEmptyString() {
        assertEquals("0x80", Rlp.encodeLong(0L).toHexString());
        assertEquals("0x80", Rlp.encodeBigInteger(BigInteger.ZERO).toHexString());
    }

    @Test
    void scalarSmallValues() {
        assertEquals("0x0f", Rlp.encodeLong(15L).toHexString());
        // 1024 = 0x0400 → 0x82 0400
        assertEquals("0x820400", Rlp.encodeLong(1024L).toHexString());
    }

    @Test
    void scalarTrimsLeadingZeros() {
        // BigInteger.toByteArray() de 255 → [0x00, 0xff] (sign byte); RLP debe dar 0x81ff
        assertEquals("0x81ff", Rlp.encodeBigInteger(BigInteger.valueOf(255)).toHexString());
    }

    @Test
    void emptyListIs0xc0() {
        assertEquals("0xc0", Rlp.encodeList(List.of()).toHexString());
    }

    @Test
    void shortList() {
        // ["cat","dog"] = 0xc8 83636174 83646f67
        Bytes encoded = Rlp.encodeList(List.of(
                Rlp.encodeBytes(Bytes.wrap("cat".getBytes())),
                Rlp.encodeBytes(Bytes.wrap("dog".getBytes()))));
        assertEquals("0xc88363617483646f67", encoded.toHexString());
    }

    @Test
    void listOfScalars() {
        // [1,2,3] → 0xc3 010203
        Bytes encoded = Rlp.encodeList(List.of(
                Rlp.encodeLong(1), Rlp.encodeLong(2), Rlp.encodeLong(3)));
        assertEquals("0xc3010203", encoded.toHexString());
    }
}
