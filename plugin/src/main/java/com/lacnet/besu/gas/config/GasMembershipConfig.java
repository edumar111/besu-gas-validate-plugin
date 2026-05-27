package com.lacnet.besu.gas.config;

import com.lacnet.besu.gas.model.Tier;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Configuración del plugin. Se carga lazy desde system properties (prefijo {@code ratelimit.*}) en
 * {@link #fromSystemProperties()}; los tests pueden usar {@link #fromProperties(Function)} para
 * inyectar un resolver custom y evitar contaminar {@link System#getProperty(String)}.
 *
 * <p>La validación es eager: si alguna property está mal formada o la dirección del contrato
 * falta, el constructor lanza inmediatamente — preferimos crashear en arranque a corromper el
 * enforcement en runtime.
 */
public final class GasMembershipConfig {

    public static final String PROP_GAS_BASIC = "ratelimit.gas.basic";
    public static final String PROP_GAS_STANDARD = "ratelimit.gas.standard";
    public static final String PROP_GAS_PREMIUM = "ratelimit.gas.premium";
    public static final String PROP_TIER_CACHE_TTL_BLOCKS = "ratelimit.tierCacheTtlBlocks";
    public static final String PROP_MEMBERSHIP_CONTRACT = "ratelimit.membershipContract";
    public static final String PROP_NODE_URL = "ratelimit.nodeUrl";

    public static final long DEFAULT_GAS_BASIC = 500_000L;
    public static final long DEFAULT_GAS_STANDARD = 5_000_000L;
    public static final long DEFAULT_GAS_PREMIUM = 10_000_000L;
    public static final int DEFAULT_TIER_CACHE_TTL_BLOCKS = 50;

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    private final Map<Tier, Long> perBlockQuotaByTier;
    private final int tierCacheTtlBlocks;
    private final String membershipContractAddress;
    private final String nodeUrl;

    private GasMembershipConfig(
            final Map<Tier, Long> perBlockQuotaByTier,
            final int tierCacheTtlBlocks,
            final String membershipContractAddress,
            final String nodeUrl) {
        this.perBlockQuotaByTier = perBlockQuotaByTier;
        this.tierCacheTtlBlocks = tierCacheTtlBlocks;
        this.membershipContractAddress = membershipContractAddress;
        this.nodeUrl = nodeUrl;
    }

    public static GasMembershipConfig fromSystemProperties() {
        return fromProperties(System::getProperty);
    }

    public static GasMembershipConfig fromProperties(final Function<String, String> resolver) {
        long basic = parsePositiveLong(resolver, PROP_GAS_BASIC, DEFAULT_GAS_BASIC);
        long standard = parsePositiveLong(resolver, PROP_GAS_STANDARD, DEFAULT_GAS_STANDARD);
        long premium = parsePositiveLong(resolver, PROP_GAS_PREMIUM, DEFAULT_GAS_PREMIUM);
        int ttl = parsePositiveInt(resolver, PROP_TIER_CACHE_TTL_BLOCKS, DEFAULT_TIER_CACHE_TTL_BLOCKS);

        String contract = trimOrNull(resolver.apply(PROP_MEMBERSHIP_CONTRACT));
        if (contract == null) {
            throw new IllegalStateException(
                    "Property '" + PROP_MEMBERSHIP_CONTRACT
                            + "' is required: address of the deployed MembershipRegistry contract");
        }
        if (!ADDRESS_PATTERN.matcher(contract).matches()) {
            throw new IllegalArgumentException(
                    "Property '" + PROP_MEMBERSHIP_CONTRACT
                            + "' must be 0x + 40 hex chars, got: " + contract);
        }

        String url = trimOrNull(resolver.apply(PROP_NODE_URL));

        Map<Tier, Long> quotas = new EnumMap<>(Tier.class);
        quotas.put(Tier.NONE, 0L);
        quotas.put(Tier.BASIC, basic);
        quotas.put(Tier.STANDARD, standard);
        quotas.put(Tier.PREMIUM, premium);
        // WHITELISTED bypassa toda lógica de cuota — el valor se interpreta como "sin límite".
        quotas.put(Tier.WHITELISTED, Long.MAX_VALUE);

        return new GasMembershipConfig(quotas, ttl, contract, url);
    }

    private static long parsePositiveLong(
            final Function<String, String> resolver, final String key, final long defaultValue) {
        String raw = trimOrNull(resolver.apply(key));
        if (raw == null) {
            return defaultValue;
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Property '" + key + "' must be a positive integer, got: " + raw, e);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Property '" + key + "' must be > 0, got: " + value);
        }
        return value;
    }

    private static int parsePositiveInt(
            final Function<String, String> resolver, final String key, final int defaultValue) {
        long asLong = parsePositiveLong(resolver, key, (long) defaultValue);
        if (asLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Property '" + key + "' must fit in int, got: " + asLong);
        }
        return (int) asLong;
    }

    private static String trimOrNull(final String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Cuota de gas por bloque para el tier dado.
     * Casos especiales: {@code NONE → 0} (las TX se rechazan antes de chequear cuota),
     * {@code WHITELISTED → Long.MAX_VALUE} (bypass).
     */
    public long getQuotaPerBlock(final Tier tier) {
        Long quota = perBlockQuotaByTier.get(tier);
        if (quota == null) {
            throw new IllegalStateException("No quota configured for tier: " + tier);
        }
        return quota;
    }

    public int getTierCacheTtlBlocks() {
        return tierCacheTtlBlocks;
    }

    public String getMembershipContractAddress() {
        return membershipContractAddress;
    }

    /**
     * URL del nodo para fallback HTTP de {@code eth_call}. Opcional: si el plugin puede usar
     * {@code TransactionSimulationService} directamente, esta property no hace falta.
     */
    public Optional<String> getNodeUrl() {
        return Optional.ofNullable(nodeUrl);
    }
}
