package com.lacnet.besu.gas.usage;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.SECPPrivateKey;
import org.hyperledger.besu.crypto.SECPSignature;
import org.hyperledger.besu.datatypes.Address;

/**
 * Implementación de {@link RecorderSigner} con {@code besu-crypto} (secp256k1) — las clases ya están
 * en el runtime de Besu, así que la dependencia es {@code compileOnly} y no se bundlea (Fase 2).
 *
 * <p>La dirección del recorder se deriva como {@code keccak256(pubKey)[12:32]}, igual que cualquier
 * cuenta Ethereum.
 */
public final class Secp256k1RecorderSigner implements RecorderSigner {

    private final SECP256K1 secp = new SECP256K1();
    private final KeyPair keyPair;
    private final Address address;

    /**
     * @param privateKeyHex clave privada en hex (con o sin prefijo {@code 0x}, 64 hex chars)
     */
    public Secp256k1RecorderSigner(final String privateKeyHex) {
        String hex = privateKeyHex.startsWith("0x") ? privateKeyHex : "0x" + privateKeyHex;
        SECPPrivateKey privateKey = secp.createPrivateKey(Bytes32.fromHexString(hex));
        this.keyPair = secp.createKeyPair(privateKey);
        Bytes pub = keyPair.getPublicKey().getEncodedBytes(); // 64 bytes sin prefijo 0x04
        this.address = Address.wrap(Hash.keccak256(pub).slice(12, 20));
    }

    @Override
    public Address address() {
        return address;
    }

    @Override
    public Signature sign(final Bytes32 messageHash) {
        SECPSignature sig = secp.sign(messageHash, keyPair);
        return new Signature(sig.getR(), sig.getS(), sig.getRecId());
    }
}
