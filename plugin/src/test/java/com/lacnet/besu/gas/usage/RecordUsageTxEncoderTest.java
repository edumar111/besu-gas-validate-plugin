package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class RecordUsageTxEncoderTest {

    private static final Address ACC = Address.fromHexString("0x00000000000000000000000000000000000000b0");
    private static final Address ACC2 = Address.fromHexString("0x00000000000000000000000000000000000000c1");
    private static final Address METER = Address.fromHexString("0x2222222222222222222222222222222222222222");
    private static final long PERIOD = 2026L * 12 + 4;

    @Test
    void callDataStartsWithSelector() {
        Bytes data = RecordUsageTxEncoder.encodeCallData(PERIOD, List.of(ACC), List.of(1000L));
        assertEquals("0x1e5092f1", data.slice(0, 4).toHexString());
    }

    @Test
    void callDataAbiLayoutForSingleEntry() {
        Bytes data = RecordUsageTxEncoder.encodeCallData(PERIOD, List.of(ACC), List.of(1000L));
        Bytes args = data.slice(4); // sin selector
        // head: periodId, offsetAccounts=0x60, offsetDeltas=0x60+32+32=0xa0
        // (cada palabra es uint256 de 32 bytes; leemos los últimos 8/4 bytes con toLong/toInt)
        assertEquals(PERIOD, args.slice(24, 8).toLong());
        assertEquals(0x60, args.slice(60, 4).toInt());
        assertEquals(0xa0, args.slice(92, 4).toInt());
        // accounts: len=1, addr
        assertEquals(1, args.slice(0x60 + 28, 4).toInt());
        assertEquals(ACC.toUnprefixedHexString(),
                args.slice(0x60 + 32 + 12, 20).toUnprefixedHexString());
        // deltas: len=1, value
        assertEquals(1, args.slice(0xa0 + 28, 4).toInt());
        assertEquals(1000L, args.slice(0xa0 + 32 + 24, 8).toLong());
    }

    @Test
    void callDataAbiLayoutForTwoEntries() {
        Bytes data = RecordUsageTxEncoder.encodeCallData(
                PERIOD, List.of(ACC, ACC2), List.of(10L, 20L));
        Bytes args = data.slice(4);
        // offsetDeltas = 0x60 + 32 + 32*2 = 0xc0
        assertEquals(0x60, args.slice(60, 4).toInt());
        assertEquals(0xc0, args.slice(92, 4).toInt());
        assertEquals(2, args.slice(0x60 + 28, 4).toInt());
        assertEquals(2, args.slice(0xc0 + 28, 4).toInt());
        assertEquals(10L, args.slice(0xc0 + 32 + 24, 8).toLong());
        assertEquals(20L, args.slice(0xc0 + 64 + 24, 8).toLong());
        // calldata total: 4 + 3*32 (head) + (32 + 2*32) accounts + (32 + 2*32) deltas
        assertEquals(4 + 96 + 96 + 96, data.size());
    }

    @Test
    void callDataLengthMismatchThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordUsageTxEncoder.encodeCallData(PERIOD, List.of(ACC), List.of(1L, 2L)));
    }

    @Test
    void unsignedTxIsAList() {
        Bytes tx = RecordUsageTxEncoder.unsignedTx(
                0L, BigInteger.ZERO, 2_000_000L, METER, BigInteger.ZERO, Bytes.fromHexString("0x1e5092f1"), 650540L);
        // lista RLP: primer byte ≥ 0xc0
        assertTrue((tx.get(0) & 0xFF) >= 0xc0);
    }

    @Test
    void unsignedTxEip155HasChainIdAndTwoZeros() {
        // tx mínima con data vacía para inspeccionar los últimos campos
        Bytes tx = RecordUsageTxEncoder.unsignedTx(
                1L, BigInteger.ZERO, 21000L, METER, BigInteger.ZERO, Bytes.EMPTY, 1L);
        // termina en ...chainId(=01), 0x80 (cero), 0x80 (cero)
        assertEquals("0x018080", tx.slice(tx.size() - 3).toHexString());
    }

    @Test
    void signedTxComputesEip155V() {
        // recId=0, chainId=650540 → v = 0 + 35 + 2*650540 = 1301115
        long chainId = 650540L;
        Bytes tx = RecordUsageTxEncoder.signedTx(
                0L, BigInteger.ZERO, 21000L, METER, BigInteger.ZERO, Bytes.EMPTY, chainId,
                0, BigInteger.ONE, BigInteger.TWO);
        long expectedV = 0 + 35 + 2 * chainId;
        assertEquals(1301115L, expectedV);
        // El RLP debe contener v como un string RLP (derivamos el encoding esperado, sin hardcodear).
        String expectedVRlp = Rlp.encodeLong(expectedV).toHexString().substring(2); // sin "0x"
        assertTrue(tx.toHexString().contains(expectedVRlp),
                "el RLP debe contener v=" + expectedV + " codificado como " + expectedVRlp);
    }

    @Test
    void signedTxRecId1ShiftsV() {
        long chainId = 650540L;
        Bytes tx0 = RecordUsageTxEncoder.signedTx(
                0L, BigInteger.ZERO, 21000L, METER, BigInteger.ZERO, Bytes.EMPTY, chainId,
                0, BigInteger.ONE, BigInteger.TWO);
        Bytes tx1 = RecordUsageTxEncoder.signedTx(
                0L, BigInteger.ZERO, 21000L, METER, BigInteger.ZERO, Bytes.EMPTY, chainId,
                1, BigInteger.ONE, BigInteger.TWO);
        // v difiere en 1 → distinta codificación
        assertTrue(!tx0.equals(tx1));
    }
}
