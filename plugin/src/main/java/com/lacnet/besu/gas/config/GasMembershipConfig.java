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

    // === Fase 1: cuotas por bloque ===
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

    // === Fase 2: cuota mensual + bloqueo + commit on-chain ===
    public static final String PROP_GAS_MONTHLY_BASIC = "ratelimit.gas.monthly.basic";
    public static final String PROP_GAS_MONTHLY_STANDARD = "ratelimit.gas.monthly.standard";
    public static final String PROP_GAS_MONTHLY_PREMIUM = "ratelimit.gas.monthly.premium";
    public static final String PROP_MONTHLY_BLOCK_DURATION_SECONDS =
            "ratelimit.monthlyBlockDurationSeconds";
    public static final String PROP_USAGE_COMMIT_EVERY_BLOCKS = "ratelimit.usage.commitEveryBlocks";
    public static final String PROP_USAGE_METER_CONTRACT = "ratelimit.usage.meterContract";
    public static final String PROP_USAGE_RECORDER = "ratelimit.usage.recorder";
    public static final String PROP_USAGE_RECORDER_KEY = "ratelimit.usage.recorderKey";
    public static final String PROP_USAGE_RECORDER_GAS_LIMIT = "ratelimit.usage.recorderGasLimit";

    /** Defaults: 30 días * 43200 bloques/día * quota per-block (ver docs/01 §3.2). */
    public static final long DEFAULT_GAS_MONTHLY_BASIC = 648_000_000_000L;     // 648 G
    public static final long DEFAULT_GAS_MONTHLY_STANDARD = 6_480_000_000_000L; // 6.48 T
    public static final long DEFAULT_GAS_MONTHLY_PREMIUM = 12_960_000_000_000L; // 12.96 T
    /** Duración del bloqueo por exceso mensual: 5 min ≈ 150 bloques con block period 2s. */
    public static final int DEFAULT_MONTHLY_BLOCK_DURATION_SECONDS = 300;
    /** Cadencia de commit batch del uso al UsageMeter: cada 150 bloques ≈ 5 min. */
    public static final int DEFAULT_USAGE_COMMIT_EVERY_BLOCKS = 150;
    /** Gas límite de la TX recordUsageBatch del recorder (holgado para batches grandes). */
    public static final long DEFAULT_USAGE_RECORDER_GAS_LIMIT = 8_000_000L;

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final Pattern PRIVKEY_PATTERN = Pattern.compile("^0x?[0-9a-fA-F]{64}$");

    private final Map<Tier, Long> perBlockQuotaByTier;
    private final Map<Tier, Long> monthlyQuotaByTier;
    private final int tierCacheTtlBlocks;
    private final int monthlyBlockDurationSeconds;
    private final int usageCommitEveryBlocks;
    private final String membershipContractAddress;
    private final String usageMeterContractAddress;
    private final boolean recorder;
    private final String recorderKey;
    private final long recorderGasLimit;
    private final String nodeUrl;

    private GasMembershipConfig(
            final Map<Tier, Long> perBlockQuotaByTier,
            final Map<Tier, Long> monthlyQuotaByTier,
            final int tierCacheTtlBlocks,
            final int monthlyBlockDurationSeconds,
            final int usageCommitEveryBlocks,
            final String membershipContractAddress,
            final String usageMeterContractAddress,
            final boolean recorder,
            final String recorderKey,
            final long recorderGasLimit,
            final String nodeUrl) {
        this.perBlockQuotaByTier = perBlockQuotaByTier;
        this.monthlyQuotaByTier = monthlyQuotaByTier;
        this.tierCacheTtlBlocks = tierCacheTtlBlocks;
        this.monthlyBlockDurationSeconds = monthlyBlockDurationSeconds;
        this.usageCommitEveryBlocks = usageCommitEveryBlocks;
        this.membershipContractAddress = membershipContractAddress;
        this.usageMeterContractAddress = usageMeterContractAddress;
        this.recorder = recorder;
        this.recorderKey = recorderKey;
        this.recorderGasLimit = recorderGasLimit;
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

        long monthlyBasic = parsePositiveLong(resolver, PROP_GAS_MONTHLY_BASIC, DEFAULT_GAS_MONTHLY_BASIC);
        long monthlyStandard =
                parsePositiveLong(resolver, PROP_GAS_MONTHLY_STANDARD, DEFAULT_GAS_MONTHLY_STANDARD);
        long monthlyPremium =
                parsePositiveLong(resolver, PROP_GAS_MONTHLY_PREMIUM, DEFAULT_GAS_MONTHLY_PREMIUM);
        int blockDurationSeconds = parsePositiveInt(
                resolver, PROP_MONTHLY_BLOCK_DURATION_SECONDS, DEFAULT_MONTHLY_BLOCK_DURATION_SECONDS);
        int commitEveryBlocks = parsePositiveInt(
                resolver, PROP_USAGE_COMMIT_EVERY_BLOCKS, DEFAULT_USAGE_COMMIT_EVERY_BLOCKS);

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

        // --- Fase 2: persistencia on-chain del uso (opcional; solo el nodo recorder commitea) ---
        String meterContract = trimOrNull(resolver.apply(PROP_USAGE_METER_CONTRACT));
        if (meterContract != null && !ADDRESS_PATTERN.matcher(meterContract).matches()) {
            throw new IllegalArgumentException(
                    "Property '" + PROP_USAGE_METER_CONTRACT
                            + "' must be 0x + 40 hex chars, got: " + meterContract);
        }
        boolean isRecorder = parseBool(resolver, PROP_USAGE_RECORDER);
        String recorderKeyValue = trimOrNull(resolver.apply(PROP_USAGE_RECORDER_KEY));
        long recorderGas =
                parsePositiveLong(resolver, PROP_USAGE_RECORDER_GAS_LIMIT, DEFAULT_USAGE_RECORDER_GAS_LIMIT);

        if (isRecorder) {
            // El nodo recorder firma y envía recordUsageBatch: necesita clave, contrato y RPC local.
            if (recorderKeyValue == null) {
                throw new IllegalStateException(
                        "Property '" + PROP_USAGE_RECORDER_KEY + "' is required when '"
                                + PROP_USAGE_RECORDER + "=true'");
            }
            if (!PRIVKEY_PATTERN.matcher(recorderKeyValue).matches()) {
                throw new IllegalArgumentException(
                        "Property '" + PROP_USAGE_RECORDER_KEY + "' must be 64 hex chars (32-byte key)");
            }
            if (meterContract == null) {
                throw new IllegalStateException(
                        "Property '" + PROP_USAGE_METER_CONTRACT + "' is required when '"
                                + PROP_USAGE_RECORDER + "=true'");
            }
            if (url == null) {
                throw new IllegalStateException(
                        "Property '" + PROP_NODE_URL + "' is required when '"
                                + PROP_USAGE_RECORDER + "=true' (eth_sendRawTransaction local)");
            }
        }

        Map<Tier, Long> quotas = new EnumMap<>(Tier.class);
        quotas.put(Tier.NONE, 0L);
        quotas.put(Tier.BASIC, basic);
        quotas.put(Tier.STANDARD, standard);
        quotas.put(Tier.PREMIUM, premium);
        // WHITELISTED bypassa toda lógica de cuota — el valor se interpreta como "sin límite".
        quotas.put(Tier.WHITELISTED, Long.MAX_VALUE);

        Map<Tier, Long> monthlyQuotas = new EnumMap<>(Tier.class);
        monthlyQuotas.put(Tier.NONE, 0L);
        monthlyQuotas.put(Tier.BASIC, monthlyBasic);
        monthlyQuotas.put(Tier.STANDARD, monthlyStandard);
        monthlyQuotas.put(Tier.PREMIUM, monthlyPremium);
        monthlyQuotas.put(Tier.WHITELISTED, Long.MAX_VALUE);

        return new GasMembershipConfig(
                quotas, monthlyQuotas, ttl, blockDurationSeconds, commitEveryBlocks, contract,
                meterContract, isRecorder, recorderKeyValue, recorderGas, url);
    }

    private static boolean parseBool(final Function<String, String> resolver, final String key) {
        String raw = trimOrNull(resolver.apply(key));
        return raw != null && (raw.equalsIgnoreCase("true") || raw.equals("1"));
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

    /**
     * Cuota de gas mensual para el tier dado (Fase 2).
     * Casos especiales: {@code NONE → 0}, {@code WHITELISTED → Long.MAX_VALUE} (bypass).
     */
    public long getMonthlyQuota(final Tier tier) {
        Long quota = monthlyQuotaByTier.get(tier);
        if (quota == null) {
            throw new IllegalStateException("No monthly quota configured for tier: " + tier);
        }
        return quota;
    }

    public int getTierCacheTtlBlocks() {
        return tierCacheTtlBlocks;
    }

    /** Duración del bloqueo por exceso mensual, en segundos (Fase 2). */
    public int getMonthlyBlockDurationSeconds() {
        return monthlyBlockDurationSeconds;
    }

    /** Duración del bloqueo por exceso mensual, en millis (conveniencia para el registry). */
    public long getMonthlyBlockDurationMillis() {
        return monthlyBlockDurationSeconds * 1000L;
    }

    /** Cada cuántos bloques el nodo recorder commitea el uso acumulado al UsageMeter (Fase 2). */
    public int getUsageCommitEveryBlocks() {
        return usageCommitEveryBlocks;
    }

    public String getMembershipContractAddress() {
        return membershipContractAddress;
    }

    /** Dirección del UsageMeter on-chain (Fase 2). Vacío si no se configuró persistencia. */
    public Optional<String> getUsageMeterContractAddress() {
        return Optional.ofNullable(usageMeterContractAddress);
    }

    /** {@code true} si este nodo es el recorder que commitea el uso on-chain (Fase 2). */
    public boolean isRecorder() {
        return recorder;
    }

    /** Clave privada del recorder (hex). Solo presente si {@link #isRecorder()}. */
    public Optional<String> getRecorderKey() {
        return Optional.ofNullable(recorderKey);
    }

    /** Gas límite de la TX recordUsageBatch del recorder (Fase 2). */
    public long getRecorderGasLimit() {
        return recorderGasLimit;
    }

    /**
     * URL del nodo para fallback HTTP de {@code eth_call}. Opcional: si el plugin puede usar
     * {@code TransactionSimulationService} directamente, esta property no hace falta.
     */
    public Optional<String> getNodeUrl() {
        return Optional.ofNullable(nodeUrl);
    }
}
