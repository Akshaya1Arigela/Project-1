package com.aerosaga.domain;

/**
 * Something the supervisor decided was worth writing down.
 *
 * <p>Incidents are the supervisor's product. They are recorded three ways:</p>
 * <ul>
 *   <li>in Temporal's history, because the workflow raised them ({@code recordIncident} is an
 *       activity, so the fact that it happened is durable),</li>
 *   <li>in the in-memory {@code IncidentLog} so the dashboard can list them without a database,
 *       and</li>
 *   <li>in Supabase's {@code incidents} table when it is configured, so they survive a restart of
 *       the whole system.</li>
 * </ul>
 *
 * @param id         stable id, derived from the scan number and the drone — never random
 * @param at         epoch millis the incident was raised
 * @param droneId    airframe involved, or {@code null} for fleet-wide incidents
 * @param missionId  mission involved, or {@code null} for fleet-wide incidents
 * @param severity   {@code HIGH}, {@code WARN} or {@code INFO}
 * @param kind       {@code LINK_LOST}, {@code LOW_BATTERY}, {@code SCAN_FAILED}…
 * @param detail     one human-readable sentence, shown in the dashboard
 * @param action     what the supervisor did: {@code NONE}, {@code RETURN_HOME_SIGNALLED},
 *                   {@code INTERVENTION_FAILED}…
 */
public record Incident(
        String id,
        long at,
        String droneId,
        String missionId,
        String severity,
        String kind,
        String detail,
        String action) {

    public static final String NONE = "NONE";
    public static final String RETURN_HOME_SIGNALLED = "RETURN_HOME_SIGNALLED";
    public static final String INTERVENTION_FAILED = "INTERVENTION_FAILED";

    public boolean intervened() {
        return RETURN_HOME_SIGNALLED.equals(action);
    }

    public Incident withAction(String newAction) {
        return new Incident(id, at, droneId, missionId, severity, kind, detail, newAction);
    }
}
