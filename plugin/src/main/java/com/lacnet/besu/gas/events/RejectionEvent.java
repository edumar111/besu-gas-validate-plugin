package com.lacnet.besu.gas.events;

import com.lacnet.besu.gas.model.Tier;
import java.time.Instant;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

/**
 * Registro de un rechazo de TX por parte del plugin. Lo emiten el selector
 * (al armar bloque) y el validator (al admitir al pool), y se expone al cliente
 * vía los métodos JSON-RPC {@code gasMembership_*} (Fase 1.6).
 *
 * @param txHash      hash de la TX rechazada
 * @param sender      cuenta emisora
 * @param tier        tier del sender al momento del rechazo
 * @param reason      una de las constantes {@code REASON_*} del selector
 * @param blockNumber bloque donde se decidió el rechazo; 0 si vino del validator (sin block context)
 * @param txGasLimit  gasLimit declarado de la TX
 * @param usedInBlock gas ya usado por el sender en ese bloque; 0 si vino del validator
 * @param quota       cuota por bloque del tier
 * @param timestamp   instante del rechazo
 * @param source      qué componente lo rechazó
 */
public record RejectionEvent(
        Hash txHash,
        Address sender,
        Tier tier,
        String reason,
        long blockNumber,
        long txGasLimit,
        long usedInBlock,
        long quota,
        Instant timestamp,
        Source source) {

    /** Qué componente del plugin originó el rechazo. */
    public enum Source {
        /** Rechazo al admitir al txpool ({@code eth_sendRawTransaction}). */
        VALIDATOR,
        /** Rechazo al armar el bloque. */
        SELECTOR
    }
}
