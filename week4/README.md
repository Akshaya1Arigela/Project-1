# AeroSaga — Week 4: The Fleet Supervisor, Incidents & Review Drills

> **Built on:** Week 3 (multi-stop routes, durable patrols, fleet analytics).
>
> **Week 4 adds:** a long-running **supervisor workflow** that watches the whole fleet, raises
> incidents when a drone goes silent mid-flight, and **intervenes** by signalling that mission to
> come home — plus a load-test endpoint for the review's "50+ moving markers" check, a readiness
> endpoint, and an `incidents` table in Supabase.

`week4/` is self-contained — you do not need to copy anything forward from `week3/`.

---

## 1. What is in here

```
week4/
├── docker-compose.yml              Temporal dev cluster (gRPC :7233, Web UI :8088, Postgres :5432)
├── temporal/dynamicconfig/         Dev-time dynamic config for the cluster
├── supabase/schema.sql             drones + missions + mission_events + incidents
├── backend/                        Spring Boot 3.5 (Java 21) + Temporal Java SDK 1.40
│   └── src/main/java/com/aerosaga/
│       ├── workflow/
│       │   ├── DroneMissionWorkflowImpl.java     Week 2 mission saga
│       │   ├── FleetMissionWorkflowImpl.java     Week 2 Promise.allOf fan-out
│       │   ├── RouteMissionWorkflowImpl.java     Week 3 multi-stop route
│       │   ├── PatrolWorkflowImpl.java           Week 3 durable patrol loop
│       │   └── FleetSupervisorWorkflowImpl.java  NEW — the observer that intervenes
│       ├── activity/                             drone activities + FleetActivities
│       │                                         (scanFleet / recordIncident / orderReturnHome)
│       ├── supervisor/                           IncidentLog, FleetSupervisorService
│       ├── analytics/                            MissionLedger + fleet summary maths
│       ├── mission/                              mission/route/patrol services + MissionIndex
│       ├── telemetry/                            simulation engine + WebSocket broadcaster
│       ├── supabase/                             REST client (service-role key stays server-side)
│       └── api/                                  missions, routes, patrols, analytics,
│                                                 supervisor, drill, simulate, health
├── frontend/                       React 18 + Vite + CesiumJS dashboard
│   └── src/components/             FleetMap, MissionPanel, ControlPanel, HistoryPanel,
│                                   RoutePanel, PatrolPanel, AnalyticsPanel, SupervisorPanel
└── src/test/java/com/aerosaga/     13 JUnit 5 tests in Temporal's in-process test environment
```

---

## 2. What is new compared with Week 3

| | Week 3 | Week 4 |
|---|---|---|
| Workflow types | 4 | **5** (adds the supervisor) |
| Who watches the fleet | a human | **a workflow, 24/7** |
| Problem detection | you notice a red marker | **incidents, raised once per outage** |
| Recovery | you press Reconnect | **the supervisor signals the mission home** |
| History growth | per-mission | **supervisor uses `continueAsNew`** |
| Review drills | manual curl | **`/api/drill/load-test`, `/api/drill/readiness`** |
| Supabase | drones, missions, events | **+ `incidents`** |
| Tests | 8 | **13** |

---

## 3. Prerequisites

- **Java 21**, **Maven**, **Node 18+**, **Docker Desktop**
- *(optional)* a **Supabase** project — everything still runs without it

---

## 4. Run it

```bash
cd week4
docker compose up -d          # Temporal: UI at http://localhost:8088

cp backend/.env.example backend/.env    # optional: add SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY

cd backend && mvn spring-boot:run
cd ../frontend && npm install && npm run dev   # http://localhost:5173
```

Tests, no Docker needed:

```bash
cd week4/backend && mvn test        # 13 tests
```

Readiness check before a demo:

```bash
curl http://localhost:8080/api/drill/readiness
```

```json
{ "temporalPolling": true, "supabaseConfigured": true, "fleetSize": 50,
  "checks": [ { "name": "temporal", "ok": true, "detail": "Worker polling the task queue" } ] }
```

---

## 5. The supervisor, in one page

`FleetSupervisorWorkflowImpl` is a workflow that never ends on its own. Every `scanIntervalSeconds`
it does this:

```
scanFleet (activity)                 ← one snapshot of all 50 airframes
for each drone on a mission:
    silent longer than grace?        → raise LINK_LOST (once per outage)
    autoIntervene?                   → orderReturnHome(workflowId)   (activity)
    below battery floor?             → raise LOW_BATTERY (once)
Workflow.await(scanInterval)         ← durable timer, or a stop signal
```

Four properties are worth defending in a review:

1. **The supervisor does not fly anything.** `orderReturnHome` sends the same `abort` signal the
   dashboard sends. The mission workflow already knows how to compensate, so the supervisor only
   has to decide *when*. Judgement lives in the workflow; flying lives in the mission.
2. **Incidents are deduped per mission.** A two-hour storm would otherwise produce 720 incidents.
   The supervisor remembers which missions it has already raised, and forgets them the moment the
   link comes back — so a *second* outage raises a *second* incident.
3. **It is deterministic.** Time comes from `Workflow.currentTimeMillis()` or from the scan's
   `scannedAt`; ids are derived (`inc-12-DRN-07`), never random; the fleet is read through an
   activity so a replay sees the same snapshot instead of a different world.
4. **It survives its own history.** Every `maxTicksPerRun` scans (default 200) it calls
   `Workflow.continueAsNew`, carrying `ticks`, `incidents` and `interventions` into the next run.
   Same workflow id, same queries, same signals, fresh history. A supervisor can run for months.

### Operator controls (all signals, no restarts)

| Action | REST | Effect |
|---|---|---|
| start | `POST /api/supervisor` | starts the workflow with a policy |
| observe only | `POST /api/supervisor/{id}/auto-intervene?enabled=false` | stops sending return-home signals |
| act again | `…?enabled=true` | resumes interventions |
| stop | `POST /api/supervisor/{id}/stop` | ends the loop, returns the final report |
| read | `GET /api/supervisor/{id}` | query: counters, fleet view, recent incidents |

---

## 6. Demos

### Demo A — the storm, supervised (the problem statement, end to end)

1. `POST /api/supervisor` with `{"scanIntervalSeconds":5,"offlineGraceSeconds":5}`.
2. Dispatch a mission (**ControlPanel**) or a 4-stop route (**RoutePanel**).
3. Press **Simulate storm**. The drone's `navigate` activity throws `DroneOfflineException`; the
   mission enters `OFFLINE_SUSPENDED` and parks.
4. Within ~10 s the supervisor's scan sees the drone silent past the grace window. It raises
   `LINK_LOST` and, because auto-intervene is on, signals the mission to abort.
5. The mission's compensation flies the drone home; the incident shows
   `→ sent home`; the analytics card records a `COMPENSATED`.
6. Open the mission in the Temporal Web UI: `WorkflowExecutionSignaled` for `abort`, then the
   compensation activities. Two workflows, one signal, no polling loop anywhere.

```bash
curl -X POST http://localhost:8080/api/supervisor \
  -H 'Content-Type: application/json' \
  -d '{"scanIntervalSeconds":5,"offlineGraceSeconds":5,"autoIntervene":true}'

curl http://localhost:8080/api/supervisor
curl http://localhost:8080/api/supervisor/incidents?limit=20
```

### Demo B — observer mode

Start the supervisor with `"autoIntervene": false` (or flip it with the query parameter above).
Trigger the storm again: the incident appears with action `NONE`, and no signal is sent. This is
the honest demo for "we want the system to tell us, not to act for us".

### Demo C — kill the supervisor's process

1. Start a supervisor, let it scan a few times.
2. `Ctrl+C` the backend. `/api/health` refuses the connection.
3. Start the backend again.
4. The supervisor is listed again with the **same id and the same counters** — it was never a
   thread in your JVM, it was a workflow in Temporal's database.

### Demo D — 50 missions at once (Mid-Project Review visual check)

```bash
curl -X POST http://localhost:8080/api/drill/load-test \
  -H 'Content-Type: application/json' -d '{"count":50,"spreadKm":7}'
```

or press **Dispatch 50 drones** in the supervisor card. Each id returned is a real
`DroneMissionWorkflow`; the globe is animating 50 live executions, not fake markers.

---

## 7. Endpoint map (Week 4 additions)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/supervisor` | start a supervisor with a scan policy |
| GET | `/api/supervisor` | running supervisors |
| GET | `/api/supervisor/{id}` | live report (query) |
| POST | `/api/supervisor/{id}/stop` | signal: end the loop |
| POST | `/api/supervisor/{id}/auto-intervene?enabled=` | signal: observe vs act |
| GET | `/api/supervisor/incidents?limit=` | incident feed, newest first |
| POST | `/api/drill/load-test` | dispatch N real missions in one burst |
| GET | `/api/drill/readiness` | Temporal / Supabase / fleet pre-demo check |

Week 2–3 endpoints (`/api/missions`, `/api/routes`, `/api/patrols`, `/api/analytics`,
`/api/simulate/*`) are unchanged.

---

## 8. Supabase

`schema.sql` now creates four tables: `drones`, `missions`, `mission_events` and **`incidents`**.

`incidents` is written by the supervisor's `recordIncident` activity through the backend (the
service-role key never reaches the browser), one row per incident:

| Column | Example |
|---|---|
| `incident_id` | `inc-12-DRN-07` |
| `drone_id`, `mission_id` | `DRN-07`, `m-abc123` (null for fleet-wide) |
| `severity`, `kind` | `HIGH`, `LINK_LOST` |
| `detail` | `No radio for 95s while flying mission m-abc123 with 61% battery` |
| `action` | `RETURN_HOME_SIGNALLED` |
| `raised_at` | `2026-11-10T07:12:44Z` |

If Supabase is not configured the activity still writes to the in-memory `IncidentLog`, so the
dashboard works on a fresh checkout. That is the same two-layer design as the Week 3 analytics
ledger: memory for the live view, Supabase for the durable record.

---

## 9. Tests (13)

| Suite | Count | What it proves |
|---|---|---|
| `FleetSupervisorWorkflowTest` | 5 | silent drone → one incident + one return-home signal; the same outage is not re-reported every scan; observer mode sends nothing; a healthy fleet is quiet; a failed scan is itself an incident |
| `RouteMissionWorkflowTest` | 3 | week 3 route saga and compensation |
| `PatrolWorkflowTest` | 2 | week 3 patrol laps and stop signal |
| `RoutePlannerTest` | 3 | deterministic stop ordering |

The supervisor tests start the real workflow, wait for real 2-second scans, then send the stop
signal and read the final report — the same path an operator takes.

---

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `POST /api/supervisor` returns 503 | Cluster not up. `docker compose up -d`, wait for `Temporal worker factory started`. |
| Supervisor runs but never raises anything | Check the **Grace (s)** value and that the drone is *on a mission*: parked drones are never incidents. |
| Incident appears, drone does not move | Auto-intervene is off, or the mission workflow is not on this task queue. Check the event history for `WorkflowExecutionSignaled`. |
| Load test returns 503 immediately | Deliberate: without a cluster, 50 sequential failed starts would look like a hang. |
| `incidents` table stays empty | Supabase not configured, or `schema.sql` not run. The dashboard still shows the in-memory feed. |
| Supervisor's `run` keeps incrementing | `continueAsNew` is working; totals are carried over. Expected on long runs. |

---

## 11. Honest limitations

- The fleet is **simulated**. `scanFleet` reads the simulator's telemetry engine, not real radios.
  Swapping in a real MQTT/GPS source means changing one activity.
- Incident persistence is best-effort: if Supabase is down, the incident exists in Temporal's
  history and the in-memory log, not in Postgres.
- Rescue is "come home", not "send another drone". Rebalancing work to a healthy airframe would be
  a second workflow and a routing problem, not a supervisor tweak.
- The supervisor scans on a timer, so detection latency is `scanInterval + grace` by construction.
  That is a policy decision, not a bug — and it is the first question to ask in a review.
