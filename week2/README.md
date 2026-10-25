# AeroSaga — Week 2: Mission Sagas, Fleet Fan-out & the Resiliency Audit

> **Week-wise plan covered:** *"Mission Sagas: Write complex Java Workflows utilizing `Promise.allOf`
> to orchestrate multiple drones, and implement `@WorkflowMethod` for state queries."* +
> *"Live Telemetry: Connect WebSockets. Animate the 3D drone markers moving across the map based on
> backend GPS streams."* + the **Mid-Project Review**: *"Start a 10-minute drone workflow. Manually
> kill the Spring Boot server halfway through, restart it, and prove the workflow resumes exactly
> where it left off."*

`week2/` is self-contained — you do not need to copy anything forward from `week1/`.

## What's here

```
week2/
├── docker-compose.yml            Temporal dev cluster (gRPC :7233, Web UI :8088, Postgres :5432)
├── temporal/dynamicconfig/       Dev-time dynamic config for the cluster
├── supabase/schema.sql           Drone registry + mission audit trail — paste into SQL Editor
├── backend/                      Spring Boot 3.5 (Java 21) + Temporal Java SDK 1.40
│   └── src/main/java/com/aerosaga/
│       ├── config/TemporalConfig.java     Worker registration (2 workflow types, 1 task queue)
│       ├── config/WorkerStatus.java       Health reporting for /api/health
│       ├── domain/                        MissionCommand, MissionSnapshot, Waypoint, phases
│       ├── workflow/
│       │   ├── DroneMissionWorkflow.java      @WorkflowMethod + @QueryMethod + @SignalMethod
│       │   ├── DroneMissionWorkflowImpl.java  Takeoff → Navigate → Drop → Return + compensation
│       │   ├── FleetMissionWorkflow.java      Promise.allOf fan-out over N child workflows
│       │   └── FleetMissionWorkflowImpl.java
│       ├── activity/DroneActivitiesImpl.java  The "real world": flies the drone, 500 ms ticks
│       ├── telemetry/                        Simulation engine + WebSocket broadcaster
│       ├── mission/MissionService.java       The only class that talks to Temporal
│       ├── supabase/                         PostgREST client (optional)
│       └── api/                              REST endpoints
└── frontend/                     React 18 + Vite 6 + CesiumJS
    └── src/
        ├── hooks/useFleetTelemetry.js  WebSocket client with auto-reconnect
        ├── lib/cesiumMap.js            Globe built with free OSM imagery (no Ion token)
        └── components/                 FleetMap, ControlPanel, MissionPanel, HistoryPanel
```

## Run it

**Terminal 1 — the cluster**

```bash
cd week2
docker compose up -d
sleep 30
open http://localhost:8088        # Temporal Web UI: namespace "default" must be Connected
```

**Terminal 2 — the backend**

```bash
cd week2/backend
mvn clean spring-boot:run
```

Wait for these two lines:

```
Worker registered on task queue 'aero-tq' with 2 workflow types
Temporal worker factory started — polling task queue 'aero-tq'
```

If Docker is **not** running, the backend still boots and then retries on its own every 5 seconds:

```
Temporal at localhost:7233 is not reachable (attempt 1): ...
Is `docker compose up -d` running? Retrying in 5s.
```

`/api/health` reports `"temporal": "not connected: <reason>"`, and every call that needs Temporal
returns **503** with *"Temporal cluster unreachable — run `docker compose up -d`"* instead of a stack
trace. Start the cluster and the worker attaches itself within ~5 s — **no backend restart needed**.

**Terminal 3 — the dashboard**

```bash
cd week2/frontend
npm install
npm run dev
```

Open **http://localhost:5173**.

## Supabase (optional, but it powers the audit trail)

Without Supabase the backend runs a 50-drone in-memory demo fleet and every endpoint still works.
With it, the drone registry and the mission state-transition history are real rows.

1. Create a free project at https://supabase.com
2. **SQL Editor** → paste [`supabase/schema.sql`](supabase/schema.sql) → **Run**
3. `cp .env.example .env` in `backend/` and fill in:
   - `SUPABASE_URL` = *Project Settings → Data API → Project URL*
   - `SUPABASE_SERVICE_ROLE_KEY` = *Project Settings → API Keys → service_role* (server-side only —
     never put this in the frontend)
4. Restart the backend. `/api/health` now says `"supabase": "connected"` and
   `GET /api/missions/history` returns the audit rows written by the workflow.

> **Why both databases?** Temporal owns *execution state* — what step each workflow is on, its
> timers, its retries. Supabase owns *our* bookkeeping: which drones exist, and an audit trail of
> every phase transition including `OFFLINE_SUSPENDED` → `NAVIGATING`. They never duplicate each
> other.

---

## Demo A — the storm scenario from the problem statement

Run all three terminals, then either use the dashboard buttons or the CLI:

```bash
# 1. dispatch a fleet — one parent workflow, N children, Promise.allOf
curl -X POST http://localhost:8080/api/fleet/dispatch \
  -H "Content-Type: application/json" \
  -d '{"missions":[
        {"droneId":"DRN-11","destinationLabel":"Drop-1","destLat":17.4100,"destLng":78.5200},
        {"droneId":"DRN-12","destinationLabel":"Drop-2","destLat":17.4300,"destLng":78.5400},
        {"droneId":"DRN-13","destinationLabel":"Drop-3","destLat":17.3900,"destLng":78.4700}]}'

# 2. a few seconds later, the storm hits
curl -X POST http://localhost:8080/api/simulate/storm -H "Content-Type: application/json" -d '{}'

# 3. watch the phase
curl http://localhost:8080/api/missions
#   "phase":"OFFLINE_SUSPENDED",
#   "detail":"Link lost during takeoff — workflow suspended durably inside Temporal"

# 4. wait as long as you like — a minute, an hour, a day

# 5. the link comes back
curl -X POST http://localhost:8080/api/simulate/reconnect
#   {"message":"Storm cleared, 3 suspended mission(s) signalled to resume"}

# 6. the missions walk themselves to DELIVERED
curl http://localhost:8080/api/missions
```

Backend log lines you should see:

```
Mission 95210503 resumed after offline suspension
Mission 95210503 DELIVERED
```

**What is actually happening.** The `navigate` activity checks the radio on every 500 ms tick and
throws `DroneOfflineException`. The workflow catches it and calls `Workflow.await(...)` — no
thread, no timer, no poller, and no row in any database polling table. Temporal has simply recorded
that the workflow is waiting for a signal. The `reconnect()` signal arrives as a Temporal signal
event, the await condition flips, and execution continues on the line after it with every field
exactly as it was.

The storm corridor is a lat/lng box centred on the home base, configured in
`application.yml` under `aerosaga.telemetry.storm` (`STORM_LAT_MIN` … `STORM_LNG_MAX`). Drones
outside that box keep flying; drones inside it also burn battery 6× faster, which is what makes the
**low-battery compensation** path reachable during a demo (`battery fell to 13.7% below the 25.0%
threshold` → `COMPENSATING` → `COMPENSATED`).

---

## Demo B — the Mid-Project Review resiliency audit

This is the "kill it halfway through" proof.

```bash
# terminal 2, with a mission suspended (Demo A step 3)
# press Ctrl+C, or just kill the JVM — the process is GONE
```

Confirm the backend is dead:

```bash
curl http://localhost:8080/api/health      # connection refused
```

Confirm Temporal still owns the state:

```bash
temporal workflow list     # or open http://localhost:8088
# Running   mission-95210503   DroneMissionWorkflow
# Running   mission-a9274d44   DroneMissionWorkflow
```

Restart it:

```bash
mvn clean spring-boot:run
```

Expected log lines, in order:

```
Connecting to Temporal at localhost:7233 (namespace default)
Worker registered on task queue 'aero-tq' with 2 workflow types
Temporal worker factory started — polling task queue 'aero-tq'
Temporal visibility returned 3 open mission workflow(s)
Re-attached to 3 open mission(s) after restart — Temporal held the state
```

Then:

```bash
curl http://localhost:8080/api/missions
#   all three still "phase":"OFFLINE_SUSPENDED" — nothing was lost, nothing was re-run

curl -X POST http://localhost:8080/api/simulate/reconnect
#   "3 suspended mission(s) signalled to resume"
```

The workflows resume at the exact activity they stalled on and finish normally.

**Where the mission list comes from after a restart** — worth understanding, because it is the
part people usually get wrong:

1. **Supabase**, when configured: `phase NOT IN (DELIVERED, COMPENSATED, FAILED, ABORTED)`.
2. **Temporal's own visibility store**, when Supabase is not configured:
   `ListWorkflowExecutions` with the query `WorkflowType = 'DroneMissionWorkflow' AND
   ExecutionStatus = 'Running'`.

Neither reads from this process's memory, which is exactly why an empty restart is a *fixable
design choice* rather than a lost mission. `MissionService.restoreRunningMissions()` rebuilds a
targeted stub per mission, re-claims the drone in the telemetry engine, and pushes the workflow's
own snapshot back onto the map.

---

## REST + WebSocket reference

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/missions` | Start one mission (returns immediately — the workflow runs on Temporal) |
| `GET` | `/api/missions` | Live snapshots of every non-terminal mission |
| `GET` | `/api/missions/{id}` | One mission, straight from its `@QueryMethod` |
| `POST` | `/api/missions/{id}/reconnect` | Signal: walk it out of `OFFLINE_SUSPENDED` |
| `POST` | `/api/missions/{id}/abort` | Signal: operator abort → compensation runs |
| `POST` | `/api/fleet/dispatch` | Start N child workflows behind one `Promise.allOf` parent |
| `GET` | `/api/fleet/state` | Home base + live mission list + suspended drones |
| `GET` | `/api/missions/history` | Supabase audit trail of phase transitions |
| `GET` | `/api/drones` · `/api/drones/{id}` · `/api/telemetry` | Registry and telemetry frames |
| `POST` | `/api/simulate/storm` | `{"active":true}` — storm on/off (off also reconnects everything) |
| `POST` | `/api/simulate/offline` | `{"droneId":"DRN-04","offline":true}` — one drone, weather-independent |
| `POST` | `/api/simulate/reconnect` | Clear the storm and signal every suspended mission |
| `GET` | `/api/simulate/status` | `stormActive`, `suspendedDrones`, `openMissions` |
| `GET` | `/api/health` | Temporal + Supabase status, drone count, running missions |
| `WS` | `ws://localhost:8080/ws/telemetry` | 1 Hz frame: `{type,serverTime,storm,homeBase,drones:[{id,callsign,lat,lng,altitudeM,batteryPct,status,phase,missionId,linkUp,updatedAt}]}` |

Single-mission start payload:

```json
{"droneId":"DRN-01","destinationLabel":"Secunderabad Depot",
 "destLat":17.4375,"destLng":78.4483,"payloadKg":1.2}
```

---

## The three things worth understanding in Week 2

**1. The saga is a single method, and the compensation is visible in it.**
`DroneMissionWorkflowImpl.runMission` is Takeoff → Navigate (per waypoint) → Drop → Return. Four
ways to leave it early — operator abort, low battery, failed drop, lost link — all funnel into
`compensate(...)`, which flies the drone home *instead of* delivering. `retryOffline(...)` is the
hinge: an ordinary exception handler would retry or fail; this one parks durably.

**2. `Promise.allOf` is a barrier over child workflow executions, not over threads.**
`FleetMissionWorkflowImpl` starts N children and blocks on `Promise.allOf(...)`. Each child has
`ParentClosePolicy = ABANDON`, so when the parent finishes it does **not** terminate missions that
are still flying, and one child failing does not take the fleet down with it.

**3. Queries and signals are different capabilities.**
A **query** (`snapshot()`) reads state without changing it and cannot be missed. A **signal**
(`reconnect()`, `abort()`) is an event appended to history — it survives a process restart and is
delivered even if the workflow is parked in `Workflow.await`. Both are addressed by workflow id,
which is why `MissionService` builds *targeted* stubs (`newWorkflowStub(Class, workflowId)`) rather
than options-based stubs: an options stub has no workflow id until **you** start a workflow through
it, and fleet children are started by their parent, not by the REST layer.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `/api/health` says `temporal: not connected` | Docker cluster not running | `docker compose up -d`, restart the backend |
| `503 Temporal cluster unreachable` | Same | Same |
| `404 No such endpoint: /actuator/health` | There is no actuator in this build | Use `/api/health` |
| Missions stay `TAKEOFF` while `stormActive` is `true` | Expected for a second or two — the activity retries 4× before the workflow suspends | Wait ~15 s for `OFFLINE_SUSPENDED` |
| Dashboard shows idle drones after a restart | Restore log missing | Check for `Re-attached to N open mission(s)` in the backend log |
| `NonDeterminismError` in the Web UI | Workflow code changed while histories existed | `docker compose down -v` to reset the dev cluster, then redispatch |
| Port 8080 already in use | A previous backend is still running | `netstat -ano \| findstr :8080` then `taskkill /PID <pid> /F` (Windows) or `kill <pid>` |

## Shutting down

```bash
cd week2
docker compose down          # stop the cluster, keep workflow history
docker compose down -v       # WIPE the Temporal database — fresh start
```

The backend and frontend are plain Ctrl+C.
