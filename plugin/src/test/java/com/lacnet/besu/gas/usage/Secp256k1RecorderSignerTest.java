package com.lacnet.besu.gas.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;

class Secp256k1RecorderSignerTest {

    // Vector conocido: clave privada de prueba de Hardhat/Anvil account #0.
    private static final String PRIVKEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address EXPECTED_ADDR =
            Address.fromHexString("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");

    @Test
    void derivesAddressFromPrivateKey() {
        Secp256k1RecorderSigner signer = new Secp256k1RecorderSigner(PRIVKEY);
        assertEquals(EXPECTED_ADDR, signer.address());
    }

    @Test
    void acceptsKeyWithoutPrefix() {
        Secp256k1RecorderSigner signer = new Secp256k1RecorderSigner(PRIVKEY.substring(2));
        assertEquals(EXPECTED_ADDR, signer.address());
    }

    @Test
    void signsProducingValidComponents() {
        Secp256k1RecorderSigner signer = new Secp256k1RecorderSigner(PRIVKEY);
        Bytes32 hash = Bytes32.fromHexString(
                "0x1111111111111111111111111111111111111111111111111111111111111111");
        RecorderSigner.Signature sig = signer.sign(hash);
        assertNotNull(sig.r());
        assertNotNull(sig.s());
        assertTrue(sig.r().signum() > 0, "r debe ser positivo");
        assertTrue(sig.s().signum() > 0, "s debe ser positivo");
        assertTrue(sig.recId() == 0 || sig.recId() == 1, "recId debe ser 0 o 1");
    }

    @Test
    void signatureIsDeterministicForSameInput() {
        // ECDSA con RFC 6979 (k determinístico) → misma firma para mismo hash+clave.
        Secp256k1RecorderSigner signer = new Secp256k1RecorderSigner(PRIVKEY);
        Bytes32 hash = Bytes32.fromHexString(
                "0x2222222222222222222222222222222222222222222222222222222222222222");
        RecorderSigner.Signature a = signer.sign(hash);
        RecorderSigner.Signature b = signer.sign(hash);
        assertEquals(a.r(), b.r());
        assertEquals(a.s(), b.s());
        assertEquals(a.recId(), b.recId());
    }
}
