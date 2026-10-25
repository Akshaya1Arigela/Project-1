package com.aerosaga.supabase;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supabase project coordinates.
 *
 * <p>Supabase exposes a full PostgREST interface over every table, which means a Java backend can
 * talk to it with nothing more than {@code spring-boot-starter-web}'s {@code RestClient}. No JDBC
 * driver, no connection pool, no JPA dialect to configure — and it works identically from a laptop
 * and from a serverless container.</p>
 *
 * @param url             <code>https://xxxx.supabase.co</code>, the project's Data API URL
 * @param serviceRoleKey  server-side full-privilege key. NEVER send this to the browser; the
 *                        frontend uses the <code>anon</code> key with Row Level Security instead.
 */
@ConfigurationProperties(prefix = "aerosaga.supabase")
public record SupabaseProperties(String url, String serviceRoleKey) {

    /** True only when both values are present — otherwise the app falls back to demo data. */
    public boolean isEnabled() {
        return url != null && !url.isBlank()
                && serviceRoleKey != null && !serviceRoleKey.isBlank()
                && !url.contains("YOUR_");
    }
}
