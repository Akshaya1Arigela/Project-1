package com.aerosaga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AeroSaga backend entry point — Week 2.
 *
 * <p>What changed from Week 1:</p>
 * <ul>
 *   <li>{@code @EnableScheduling} powers the telemetry simulator, which is what pushes the
 *       50-drone GPS stream out over WebSockets.</li>
 *   <li>A second workflow, {@code FleetMissionWorkflow}, fans out to N drone missions and waits
 *       on {@code Promise.allOf}.</li>
 *   <li>The drone mission saga gained compensation and a durable offline suspension — the whole
 *       point of the problem statement.</li>
 *   <li>Supabase is now written to as well as read from: the mission audit trail.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AeroSagaApplication {

    public static void main(String[] args) {
        // BEFORE Spring resolves application.yml: this is the only point at which a value read
        // from backend/.env can still satisfy a ${SUPABASE_URL:} placeholder. See config.DotEnv.
        com.aerosaga.config.DotEnv.load();
        SpringApplication.run(AeroSagaApplication.class, args);
    }
}
