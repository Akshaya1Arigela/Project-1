package com.aerosaga.config;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Whether this process's Temporal worker has actually reached the cluster.
 *
 * <p>Separate from Spring's liveness on purpose: the REST API can be perfectly healthy while the
 * worker has not managed to join the cluster yet (cluster still starting, laptop resumed from
 * sleep, wrong port). The dashboard shows these as two different lights so you can tell the
 * difference at a glance during a demo.</p>
 */
@Component
public class WorkerStatus {

    private volatile boolean polling;
    private volatile String lastError;
    private volatile Instant connectedAt;

    public void markPolling() {
        this.polling = true;
        this.lastError = null;
        this.connectedAt = Instant.now();
    }

    public void markFailed(String message) {
        this.polling = false;
        this.lastError = message;
    }

    public boolean isPolling() {
        return polling;
    }

    public String getLastError() {
        return lastError;
    }

    public String describe() {
        if (polling) {
            return "polling";
        }
        return "not connected" + (lastError == null ? "" : ": " + lastError);
    }
}
