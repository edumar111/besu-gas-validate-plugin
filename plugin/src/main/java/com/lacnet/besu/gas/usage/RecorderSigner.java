package com.lacnet.besu.gas.usage;

import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;

/**
 * Firma el hash de una transacción con la clave del nodo recorder (Fase 2). Aísla la dependencia
 * de {@code besu-crypto} (secp256k1) del resto del committer, que es puro y testeable con fakes.
 */
public interface RecorderSigner {

    /** Dirección de la cuenta recorder (derivada de su clave pública). */
    Address address();

    /**
     * Firma un hash de 32 bytes (keccak256 del RLP de firma EIP-155).
     *
     * @param messageHash hash a firmar
     * @return componentes de la firma ECDSA
     */
    Signature sign(Bytes32 messageHash);

    /** Componentes de una firma ECDSA secp256k1. */
    record Signature(BigInteger r, BigInteger s, int recId) {}
}
