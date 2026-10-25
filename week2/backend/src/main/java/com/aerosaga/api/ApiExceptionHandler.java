package com.aerosaga.api;

import com.aerosaga.mission.MissionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns exceptions into a consistent JSON error body so the dashboard can show real messages. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MissionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> missionNotFound(MissionNotFoundException e) {
        // Log the cause: a 404 here means "the query was rejected", not "no such mission", and
        // the two need very different fixes (one is a typo, the other is a worker problem).
        log.warn("Mission query failed: {}", e.getMessage(), e.getCause());
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Every call that reaches Temporal surfaces here when the cluster is down. Turning it into a
     * 503 with an actionable message means the dashboard can say "start Docker" instead of
     * "Unexpected server error" — which is exactly the state you are in on first run.
     */
    @ExceptionHandler(io.temporal.client.WorkflowServiceException.class)
    public ResponseEntity<Map<String, Object>> temporalUnreachable(
            io.temporal.client.WorkflowServiceException e) {
        log.warn("Temporal cluster unreachable: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "Temporal cluster unreachable — run `docker compose up -d` and retry");
    }

    /**
     * A typo in a URL is a 404, not a server bug. Without this, Spring's
     * {@code NoResourceFoundException} falls into the catch-all below and the dashboard shows
     * "Internal Server Error" for a mistyped path.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "No such endpoint: " + e.getResourcePath());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /** Anything else is a bug in this process — log it properly, then return a generic 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled API error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error — see backend logs");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
