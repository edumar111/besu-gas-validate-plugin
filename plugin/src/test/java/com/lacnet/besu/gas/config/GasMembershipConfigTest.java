package com.lacnet.besu.gas.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lacnet.besu.gas.model.Tier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GasMembershipConfigTest {

    private static final String VALID_ADDRESS = "0x1234567890123456789012345678901234567890";

    private static Function<String, String> propsFrom(final Map<String, String> props) {
        return props::get;
    }

    private static Map<String, String> withContract(final Map<String, String> extra) {
        Map<String, String> map = new HashMap<>(extra);
        map.put(GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT, VALID_ADDRESS);
        return map;
    }

    @Test
    void defaultsUsedWhenPropertiesAbsent() {
        GasMembershipConfig cfg = GasMembershipConfig.fromProperties(
                propsFrom(withContract(Map.of())));

        assertEquals(500_000L, cfg.getQuotaPerBlock(Tier.BASIC));
        assertEquals(5_000_000L, cfg.getQuotaPerBlock(Tier.STANDARD));
        assertEquals(10_000_000L, cfg.getQuotaPerBlock(Tier.PREMIUM));
        assertEquals(0L, cfg.getQuotaPerBlock(Tier.NONE));
        assertEquals(Long.MAX_VALUE, cfg.getQuotaPerBlock(Tier.WHITELISTED));
        assertEquals(50, cfg.getTierCacheTtlBlocks());
        assertEquals(VALID_ADDRESS, cfg.getMembershipContractAddress());
        assertTrue(cfg.getNodeUrl().isEmpty());
    }

    @Test
    void respectsOverridesFromProperties() {
        GasMembershipConfig cfg = GasMembershipConfig.fromProperties(propsFrom(Map.of(
                GasMembershipConfig.PROP_GAS_BASIC, "1000000",
                GasMembershipConfig.PROP_GAS_STANDARD, "10000000",
                GasMembershipConfig.PROP_GAS_PREMIUM, "20000000",
                GasMembershipConfig.PROP_TIER_CACHE_TTL_BLOCKS, "100",
                GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT, VALID_ADDRESS,
                GasMembershipConfig.PROP_NODE_URL, "http://localhost:4545")));

        assertEquals(1_000_000L, cfg.getQuotaPerBlock(Tier.BASIC));
        assertEquals(10_000_000L, cfg.getQuotaPerBlock(Tier.STANDARD));
        assertEquals(20_000_000L, cfg.getQuotaPerBlock(Tier.PREMIUM));
        assertEquals(100, cfg.getTierCacheTtlBlocks());
        assertEquals(Optional.of("http://localhost:4545"), cfg.getNodeUrl());
    }

    @Test
    void requiresMembershipContractAddress() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(Map.of())));
        assertTrue(ex.getMessage().contains(GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT));
    }

    @Test
    void rejectsBlankContractAddress() {
        assertThrows(IllegalStateException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(Map.of(
                        GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT, "   "))));
    }

    @Test
    void rejectsMalformedContractAddress() {
        // sin 0x
        assertThrows(IllegalArgumentException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(Map.of(
                        GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT,
                        "1234567890123456789012345678901234567890"))));
        // longitud incorrecta
        assertThrows(IllegalArgumentException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(Map.of(
                        GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT, "0xdeadbeef"))));
        // caracteres no hex
        assertThrows(IllegalArgumentException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(Map.of(
                        GasMembershipConfig.PROP_MEMBERSHIP_CONTRACT,
                        "0xZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"))));
    }

    @Test
    void rejectsZeroGasQuota() {
        assertThrows(IllegalArgumentException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(withContract(Map.of(
                        GasMembershipConfig.PROP_GAS_BASIC, "0")))));
    }

    @Test
    void rejectsNegativeGasQuota() {
        assertThrows(IllegalArgumentException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(withContract(Map.of(
                        GasMembershipConfig.PROP_GAS_STANDARD, "-1")))));
    }

    @Test
    void rejectsNonNumericGasQuota() {
        assertThrows(IllegalArgumentException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(withContract(Map.of(
                        GasMembershipConfig.PROP_GAS_PREMIUM, "abc")))));
    }

    @Test
    void rejectsZeroTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> GasMembershipConfig.fromProperties(propsFrom(withContract(Map.of(
                        GasMembershipConfig.PROP_TIER_CACHE_TTL_BLOCKS, "0")))));
    }

    @Test
    void blankNodeUrlTreatedAsAbsent() {
        GasMembershipConfig cfg = GasMembershipConfig.fromProperties(propsFrom(withContract(Map.of(
                GasMembershipConfig.PROP_NODE_URL, "   "))));
        assertTrue(cfg.getNodeUrl().isEmpty());
    }
}
