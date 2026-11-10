package com.aerosaga.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The Telemetry Gateway: receives nothing, pushes everything.
 *
 * <p>A "gateway" in the problem statement could also mean ingesting pings from real drones. The
 * shape is identical — an activity (or an HTTP endpoint) writes into {@link TelemetryEngine},
 * and this handler drains that engine once a tick and fans the frame out to every browser. The
 * engine is the buffer between the two rates, which is what stops a slow browser from stalling
 * a drone.</p>
 *
 * <p>Two things this class gets right that bite people later:</p>
 * <ul>
 *   <li><b>Dead sessions are removed</b>, not left in the set to accumulate exceptions.</li>
 *   <li><b>Sends are serialised per session</b>. A {@code WebSocketSession} is not thread-safe,
 *       and a broadcast racing a close will throw {@code IllegalStateException}.</li>
 * </ul>
 */
@Component
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Telemetry client connected: {} ({} total)", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Telemetry client disconnected: {} ({} total)", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Telemetry transport error on {}: {}", session.getId(), exception.getMessage());
        sessions.remove(session);
        closeQuietly(session);
    }

    /** The browser has nothing to say; this is a one-way stream. */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Ignoring client message on {}: {}", session.getId(), message.getPayload());
    }

    /** Send one fleet frame to every connected dashboard. */
    public void broadcast(String json) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage payload = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                // WebSocketSession is not thread-safe — one writer at a time per session.
                synchronized (session) {
                    session.sendMessage(payload);
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping telemetry client {}: {}", session.getId(), e.getMessage());
                sessions.remove(session);
                closeQuietly(session);
            }
        }
    }

    public int clientCount() {
        return sessions.size();
    }

    public List<WebSocketSession> sessionsSnapshot() {
        return List.copyOf(sessions);
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NO_CLOSE_FRAME);
            }
        } catch (IOException ignored) {
            // Already gone.
        }
    }
}
