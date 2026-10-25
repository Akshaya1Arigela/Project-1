package com.aerosaga.telemetry;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Expose <code>ws://localhost:8080/ws/telemetry</code>.
 *
 * <p>Deliberately a raw WebSocket rather than STOMP over SockJS. The dashboard is a single
 * high-frequency broadcast — one JSON frame per second for 50 drones — and a bare
 * <code>TextMessage</code> per tick is far cheaper than wrapping every frame in a STOMP envelope.
 * You would only want STOMP if you needed topic subscriptions and per-client routing.</p>
 */
@Configuration
@EnableWebSocket
public class TelemetryWebSocketConfig implements WebSocketConfigurer {

    private final TelemetryWebSocketHandler handler;

    public TelemetryWebSocketConfig(TelemetryWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/telemetry")
                // In development the Vite dev server proxies /ws to the backend, so the browser
                // sends Origin: http://localhost:5173. Keep this list in sync with vite.config.js.
                .setAllowedOrigins(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:4173");
    }
}
