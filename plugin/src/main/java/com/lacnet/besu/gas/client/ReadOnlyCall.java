package com.lacnet.besu.gas.client;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.AccessListEntry;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.CallParameter;
import org.hyperledger.besu.datatypes.CodeDelegation;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.datatypes.Wei;

/**
 * Implementación mínima de {@link CallParameter} para una lectura read-only (eth_call interno vía
 * {@code TransactionSimulationService}). Sólo seteamos {@code to}, {@code payload} y un gas budget;
 * el resto queda en defaults ({@code Optional.empty}).
 *
 * <p>Compartida por {@link MembershipContractClient} y {@code UsageMeterClient} — ambos hacen el
 * mismo tipo de llamada de lectura a un contrato. La implementamos a mano (en vez de usar
 * {@code ImmutableCallParameter}) para no acoplarnos a un tipo del paquete internal de Besu.
 */
public final class ReadOnlyCall implements CallParameter {

    /** Gas suficiente para una llamada read-only (sobreestimado a propósito). */
    public static final long READ_CALL_GAS = 100_000L;

    private final Address to;
    private final Bytes payload;

    public ReadOnlyCall(final Address to, final Bytes payload) {
        this.to = to;
        this.payload = payload;
    }

    @Override
    public Optional<BigInteger> getChainId() {
        return Optional.empty();
    }

    @Override
    public Optional<Address> getSender() {
        return Optional.empty();
    }

    @Override
    public Optional<Address> getTo() {
        return Optional.of(to);
    }

    @Override
    public OptionalLong getGas() {
        return OptionalLong.of(READ_CALL_GAS);
    }

    @Override
    public Optional<Wei> getMaxPriorityFeePerGas() {
        return Optional.empty();
    }

    @Override
    public Optional<Wei> getMaxFeePerGas() {
        return Optional.empty();
    }

    @Override
    public Optional<Wei> getMaxFeePerBlobGas() {
        return Optional.empty();
    }

    @Override
    public Optional<Wei> getGasPrice() {
        return Optional.empty();
    }

    @Override
    public Optional<Wei> getValue() {
        return Optional.empty();
    }

    @Override
    public Optional<List<AccessListEntry>> getAccessList() {
        return Optional.empty();
    }

    @Override
    public Optional<List<VersionedHash>> getBlobVersionedHashes() {
        return Optional.empty();
    }

    @Override
    public OptionalLong getNonce() {
        return OptionalLong.empty();
    }

    @Override
    public Optional<Boolean> getStrict() {
        return Optional.empty();
    }

    @Override
    public Optional<Bytes> getPayload() {
        return Optional.of(payload);
    }

    @Override
    public List<CodeDelegation> getCodeDelegationAuthorizations() {
        return List.of();
    }
}
