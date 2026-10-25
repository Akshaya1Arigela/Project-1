package com.aerosaga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AeroSaga backend entry point.
 *
 * <p>Week 1 scope: one worker on task queue {@code aero-tq} running a single
 * {@code DroneMissionWorkflow} whose only side effect is the Takeoff activity.
 * Week 2 adds the full saga, compensation, fleet fan-out and the WebSocket gateway.</p>
 */
@SpringBootApplication
// Picks up @ConfigurationProperties records such as SupabaseProperties without any @Bean glue.
@ConfigurationPropertiesScan
public class AeroSagaApplication {

    public static void main(String[] args) {
        // BEFORE Spring resolves application.yml: this is the only point at which a value read
        // from backend/.env can still satisfy a ${SUPABASE_URL:} placeholder. See config.DotEnv.
        com.aerosaga.config.DotEnv.load();
        SpringApplication.run(AeroSagaApplication.class, args);
    }
}
