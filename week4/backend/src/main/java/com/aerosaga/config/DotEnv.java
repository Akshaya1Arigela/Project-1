package com.aerosaga.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code .env} and exposes it to Spring's <b>property resolution</b> — nothing else.
 *
 * <h2>Why this class has to exist</h2>
 * <p>Spring Boot resolves <code>${SUPABASE_URL:}</code> from its own {@code Environment}:
 * OS environment variables, system properties, {@code application.yml}. It does <b>not</b> read a
 * {@code .env} file, and neither does Maven. So {@code cp .env.example .env} on its own is
 * theatre — the file sits there, {@code supabase.isEnabled()} stays {@code false}, and the app
 * quietly falls back to demo data with no error to tell you why.</p>
 *
 * <p>This loads that file <em>before</em> {@code SpringApplication.run}, which is the only moment
 * a system property can still influence <code>${...}</code> placeholders in
 * {@code application.yml}.</p>
 *
 * <h2>Precedence, deliberately conservative</h2>
 * <ol>
 *   <li>Real OS environment variables always win — that is how CI and Docker pass secrets.</li>
 *   <li>Then {@code -D} system properties from the command line.</li>
 *   <li>Only then {@code .env}.</li>
 * </ol>
 * <p>Only the key <em>names</em> are ever logged. The values are credentials.</p>
 */
public final class DotEnv {

    private static final Logger log = LoggerFactory.getLogger(DotEnv.class);

    /** Where Spring Boot's working directory puts it: {@code week2/backend/.env}. */
    private static final List<Path> CANDIDATES = List.of(
            Paths.get(".env"),
            Paths.get("backend", ".env"));

    private DotEnv() {
    }

    /**
     * Called from {@code AeroSagaApplication.main}. Safe to call twice, safe when the file is
     * absent, and silent about everything except the keys it actually applied.
     */
    public static void load() {
        for (Path candidate : CANDIDATES) {
            if (Files.isRegularFile(candidate)) {
                load(candidate);
                return;
            }
        }
        log.info("No .env file found — using OS environment variables only "
                + "(copy .env.example to .env to configure Supabase)");
    }

    private static void load(Path file) {
        Map<String, String> entries = new LinkedHashMap<>();
        try {
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
                        || (value.startsWith("'") && value.endsWith("'") && value.length() >= 2)) {
                    value = value.substring(1, value.length() - 1);
                }
                entries.put(key, value);
            }
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return;
        }

        int applied = 0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = entry.getKey();
            boolean inEnvironment = System.getenv(key) != null;
            boolean alreadySet = System.getProperty(key) != null;
            if (!inEnvironment && !alreadySet) {
                System.setProperty(key, entry.getValue());
                applied++;
            }
        }
        log.info("Loaded {} setting(s) from {} ({} already set by the environment): {}",
                entries.size(), file, entries.size() - applied, entries.keySet());
    }
}
