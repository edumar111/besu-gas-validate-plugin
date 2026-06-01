package com.lacnet.besu.gas.usage;

import com.lacnet.besu.gas.model.Tier;
import org.hyperledger.besu.datatypes.Address;

/**
 * Resuelve el tier de una cuenta para un bloque dado. En producción se implementa con la
 * {@code TierCache} compartida + {@code MembershipContractClient} (el mismo lookup que usan el
 * selector y el validator), de modo que un solo {@code eth_call} por {@code (cuenta, bloque)} sirve
 * a todos los componentes.
 */
@FunctionalInterface
public interface TierResolver {
    Tier tierOf(Address account, long blockNumber);
}
