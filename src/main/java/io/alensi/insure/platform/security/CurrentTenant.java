package io.alensi.insure.platform.security;

import java.util.function.Supplier;

/**
 * The tenant the current thread acts in. Set per request by {@link TenantInterceptor}, and by jobs
 * and event consumers from the event's {@code tenantCode} with {@link #call}. Every transaction
 * hands it to PostgreSQL ({@link TenantTransactionManager}), so row-level security applies.
 */
public final class CurrentTenant {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    private CurrentTenant() {
    }

    /** The tenant code; fails if none is set, so code can never silently act without a tenant. */
    public static String require() {
        String tenant = TENANT.get();
        if (tenant == null) {
            throw new IllegalStateException("No tenant for this thread");
        }
        return tenant;
    }

    static String get() {
        return TENANT.get();
    }

    /** Runs {@code work} in {@code tenant}, restoring the previous tenant afterwards. */
    public static <T> T call(String tenant, Supplier<T> work) {
        String previous = TENANT.get();
        set(tenant);
        try {
            return work.get();
        } finally {
            set(previous);
        }
    }

    public static void run(String tenant, Runnable work) {
        call(tenant, () -> {
            work.run();
            return null;
        });
    }

    static void set(String tenant) {
        if (tenant == null) {
            TENANT.remove();
        } else {
            TENANT.set(tenant);
        }
    }
}
