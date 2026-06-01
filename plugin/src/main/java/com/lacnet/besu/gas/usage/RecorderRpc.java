package com.lacnet.besu.gas.usage;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * Acceso JSON-RPC que el recorder usa para enviar su commit on-chain (Fase 2). La plugin-api de
 * Besu no permite inyectar TXs al pool, así que el recorder firma y envía vía el
 * {@code eth_sendRawTransaction} del propio nodo. Aislado tras esta interfaz para testear el
 * committer con un fake.
 */
public interface RecorderRpc {

    /**
     * Nonce pendiente de la cuenta ({@code eth_getTransactionCount(address, "pending")}).
     *
     * @param account cuenta recorder
     * @return próximo nonce a usar
     */
    long pendingNonce(Address account);

    /**
     * Envía una TX firmada ({@code eth_sendRawTransaction}).
     *
     * @param signedRawTx RLP de la TX firmada
     * @return hash de la TX devuelto por el nodo
     */
    String sendRawTransaction(Bytes signedRawTx);
}
