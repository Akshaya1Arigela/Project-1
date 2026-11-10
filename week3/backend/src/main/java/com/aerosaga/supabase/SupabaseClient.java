package com.aerosaga.supabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * A ~60 line Supabase client. That is genuinely all it takes.
 *
 * <p>Supabase's Data API <em>is</em> PostgREST: <code>GET /rest/v1/&lt;table&gt;?select=*</code>
 * with an <code>apikey</code> header returns JSON rows. So we build a Spring {@link RestClient}
 * against the project URL and we are done.</p>
 *
 * <p>When Supabase is not configured every method returns <code>null</code> and callers fall back
 * to demo data — the whole app runs with no Supabase account at all.</p>
 */
@Component
public class SupabaseClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseClient.class);

    private final RestClient rest;
    private final boolean enabled;

    public SupabaseClient(SupabaseProperties properties) {
        this.enabled = properties.isEnabled();
        if (enabled) {
            log.info("Supabase enabled at {}", properties.url());
            this.rest = RestClient.builder()
                    .baseUrl(properties.url())
                    .defaultHeader("apikey", properties.serviceRoleKey())
                    .defaultHeader("Authorization", "Bearer " + properties.serviceRoleKey())
                    .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .build();
        } else {
            log.warn(
                    "Supabase is NOT configured (aerosaga.supabase.url / service-role-key). "
                            + "Falling back to the in-memory demo fleet. See backend/.env.example.");
            this.rest = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * GET rows from a table.
     *
     * @param table       table name, already URL-safe
     * @param query       e.g. {@code "select=*&order=id.asc"} — leave null for plain select=*
     * @param elementType the row DTO class
     */
    public <T> List<T> select(String table, String query, Class<T> elementType) {
        if (!enabled) {
            return null;
        }
        String uri = "/rest/v1/" + table + (query == null || query.isBlank() ? "" : "?" + query);
        try {
            return rest.get()
                    .uri(uri)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<T>>() {
                    });
        } catch (RuntimeException e) {
            // Never let a Supabase hiccup take down the mission orchestrator.
            log.error("Supabase select failed on {} -> {}", uri, e.getMessage());
            return null;
        }
    }

    /** Insert or update ({@code Prefer: resolution=merge-duplicates} makes it an upsert). */
    public boolean upsert(String table, Map<String, Object> row) {
        if (!enabled) {
            return false;
        }
        try {
            rest.post()
                    .uri("/rest/v1/" + table)
                    .header("Prefer", "resolution=merge-duplicates,return=minimal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(row)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.error("Supabase upsert failed on {} -> {}", table, e.getMessage());
            return false;
        }
    }
}
