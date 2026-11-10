# AeroSaga — Week 3: Multi-Stop Routes, Durable Patrols & Fleet Analytics

> **Built on:** Week 2 (mission saga, `Promise.allOf` fan-out, WebSocket telemetry, offline
> suspension, Supabase audit trail).
>
> **Week 3 adds:** a route workflow that visits several stops on one battery, a patrol workflow
> whose waiting is a *durable timer* instead of a cron job, and a fleet analytics ledger that is
> computed in Java so it works with no database configured.

`week3/` is self-contained — you do not need to copy anything forward from `week2/`.

---

## 1. What is in here

```
week3/
├── docker-compose.yml              Temporal dev cluster (gRPC :7233, Web UI :8088, Postgres :5432)
├── temporal/dynamicconfig/         Dev-time dynamic config for the cluster
├── supabase/schema.sql             Drone registry + mission audit trail — paste into SQL Editor
├── backend/                        Spring Boot 3.5 (Java 21) + Temporal Java SDK 1.40
│   └── src/main/java/com/aerosaga/
│       ├── config/TemporalConfig.java      Worker registration (4 workflow types, 1 task queue)
│       ├── config/WorkerStatus.java        /api/health reporting
│       ├── domain/                         MissionCommand, RouteCommand, RouteProgress,
│       │                                   PatrolCommand, PatrolStatus, Waypoint, phases…
│       ├── workflow/
│       │   ├── DroneMissionWorkflowImpl.java   Week 2 saga, unchanged
│       │   ├── FleetMissionWorkflowImpl.java   Week 2 fan-out, unchanged
│       │   ├── RouteMissionWorkflowImpl.java   NEW — takeoff once, N stops, return once
│       │   └── PatrolWorkflowImpl.java         NEW — recurring laps parked on a durable timer
│       ├── activity/                       takeoff / navigate / drop / return / compensate
│       │                                   / reportPhase / planRoute
│       ├── analytics/                      MissionLedger (in-memory event log) + summary maths
│       ├── mission/                        MissionService, RouteMissionService, PatrolService
│       ├── telemetry/                      Simulation engine + WebSocket broadcaster
│       ├── supabase/                       Supabase REST client (service-role key stays server-side)
│       └── api/                            /api/missions, /api/routes, /api/patrols,
│                                           /api/analytics, /api/simulate, /api/health
├── frontend/                       React 18 + Vite + CesiumJS dashboard
│   └── src/components/             FleetMap, MissionPanel, ControlPanel, HistoryPanel,
│                                   RoutePanel, PatrolPanel, AnalyticsPanel
└── src/test/java/com/aerosaga/     8 JUnit 5 tests, run inside Temporal's test environment
```

---

## 2. What is new compared with Week 2

| | Week 2 | Week 3 |
|---|---|---|
| Workflow types | 2 (mission, fleet) | **4** (mission, fleet, route, patrol) |
| One flight | 1 destination | **N stops, optimised by an activity** |
| Scheduling | none | **patrol laps with durable timers** |
| Fleet insight | live positions | **delivered / compensated / success rate / throughput** |
| Tests | — | **8 tests in `TestWorkflowEnvironment`** |

---

## 3. Prerequisites

- **Java 21** and **Maven** (`java -version`, `mvn -version`)
- **Node 18+** and npm
- **Docker Desktop** — for the Temporal cluster
- *(optional)* a **Supabase** project — without it everything still runs, using an in-memory demo
  fleet of 50 drones

---

## 4. Run it

### 4.1 Temporal cluster

```bash
cd week3
docker compose up -d
```

Check the Web UI at <http://localhost:8088>. It is the single best debugging tool in this project:
it shows every workflow, every activity retry and every signal.

If Docker is not available you can still run the backend and frontend — they boot, serve the REST
API and show a "Temporal unreachable" health status. Only starting missions will fail.

### 4.2 Supabase (optional)

1. Create a project at <https://supabase.com>.
2. Open **SQL Editor** and run [`supabase/schema.sql`](supabase/schema.sql).
3. Copy the project URL and the **service_role** key into `backend/.env`:

```bash
cp backend/.env.example backend/.env
# then edit backend/.env
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_SERVICE_ROLE_KEY=eyJhbGciOi...
```

The backend loads `.env` itself (`config/DotEnv.java`), so you do not need to export variables in
your shell. Never put the service-role key in the frontend.

### 4.3 Backend

```bash
cd week3/backend
mvn spring-boot:run
```

Wait for `Temporal worker factory started — polling task queue 'aero-tq'`, then check:

```bash
curl http://localhost:8080/api/health
```

### 4.4 Frontend

```bash
cd week3/frontend
npm install
npm run dev
```

Open <http://localhost:5173>. Vite proxies `/api` and `/ws` to port 8080, so there is no CORS setup.

### 4.5 Tests

```bash
cd week3/backend
mvn test
```

The route and patrol workflows are exercised in Temporal's **in-process test environment**, so the
suite passes without Docker and without a cluster.

---

## 5. Week 3 demos

### Demo A — multi-stop route (one battery, four parcels)

1. In **Multi-stop route**, pick a drone and press **Fly 4-stop route**.
2. The four stops are sent in a deliberately shuffled order. The `planRoute` activity re-orders
   them, and the panel prints **Flown order: … → …**, which is the cheapest proof that the
   optimisation happened inside the workflow.
3. Watch the route card: `0/4 → 1/4 → …` stops delivered, plus the planned distance.
4. Open the workflow in the Temporal Web UI: the activity result (the ordered list) is sitting in
   the event history, so a replay can never pick a different order.

REST equivalent:

```bash
curl -X POST http://localhost:8080/api/routes -H 'Content-Type: application/json' -d '{
  "droneId": "DRN-04",
  "payloadKg": 2.4,
  "stops": [
    {"label": "Uppal Cross-dock", "lat": 17.4126, "lng": 78.5522},
    {"label": "Gachibowli Fulfilment", "lat": 17.4401, "lng": 78.3489},
    {"label": "Secunderabad Depot", "lat": 17.4375, "lng": 78.4483},
    {"label": "HITEC City Hub", "lat": 17.4435, "lng": 78.3772}
  ]
}'
```

```bash
curl http://localhost:8080/api/routes
```

**Compensation on a route:** if the battery drops below the threshold after stop *k*, the remaining
stops are abandoned, the parcels still on board go back to inventory, and the drone flies home.
Partial success is a legal outcome and the card says exactly how many stops were delivered.

### Demo B — patrol with a durable timer

1. In **Patrols**, choose a drone, interval `30`, laps `3`, press **Start patrol**.
2. The card counts down: `Lap 1/3 · next in 30s`.
3. Kill the backend while it is counting down (`Ctrl+C`), start it again.
4. Within a few seconds the patrol comes back in the list at the *same* lap and the *remaining*
   countdown — because the wait is a timer stored in Temporal, not a thread in your JVM.

```bash
curl -X POST http://localhost:8080/api/patrols -H 'Content-Type: application/json' \
  -d '{"droneId":"DRN-03","intervalSeconds":30,"cycles":3}'

curl http://localhost:8080/api/patrols

curl -X DELETE http://localhost:8080/api/patrols/<patrolId>
```

`DELETE` sends a **signal** — it does not delete history. The lap already in the air is allowed to
land, then the patrol stops. Every decision stays queryable in the Web UI weeks later.

### Demo C — fleet analytics

The **Fleet analytics** card is fed by `GET /api/analytics/summary`:

| Field | Meaning |
|---|---|
| `missionsRecorded` | missions in the in-memory ledger |
| `missionsInFlight` | workflows the service still holds as running |
| `delivered`, `compensated`, `failed`, `aborted` | outcome counts |
| `successRate` | delivered ÷ terminal missions |
| `averageFlightSeconds` | first event → terminal event, averaged |
| `suspensionEvents` | how many `OFFLINE_SUSPENDED` transitions happened |
| `throughput[]` | deliveries per five-minute bucket (the little bar chart) |
| `drones[]` | per-airframe roll-up, capped to six rows on screen |

The timeline is the "prove it" endpoint:

```bash
curl http://localhost:8080/api/analytics/timeline/<missionId>
```

After a storm drill it shows `OFFLINE_SUSPENDED` at one timestamp and `NAVIGATING` minutes later,
with nothing in between — a durable suspension, visible as data.

---

## 6. How the new pieces work

### RouteMissionWorkflow

```
planRoute (activity)      ← ordered stops + total distance, written into history
takeoff                   ← once for the whole route
for each stop:
    navigate              ← may suspend on DroneOfflineException
    dropParcel            ← delivered, or refused
    (low battery?) → compensate: abandon remaining stops, fly home
returnToBase              ← once
```

Three deterministic rules make this safe to replay:

1. Every stop's coordinates come from the **command argument**, not from a live lookup.
2. The stop **order** comes from an activity result, so a replay reuses the same order.
3. Mission ids are **derived**, never random, inside the workflow.

### PatrolWorkflow

```
for cycle in 1..cycles:
    child DroneMissionWorkflow("patrolId-c<cycle>")   ← its own history, queryable separately
    Workflow.await(interval, () -> stopRequested)      ← durable timer, zero threads
```

* `Workflow.await` **parks** the workflow. A two-hour gap costs one timer, not one sleeping thread.
* Child workflow ids are derived (`patrol-<id>` parent, `mission-<id>-c3` child): after a restart
  the same lap cannot be started twice.
* A lap that fails does **not** end the patrol. A schedule that dies on the first bad delivery is
  not a schedule.

### MissionLedger

`analytics/MissionLedger.java` keeps a bounded, thread-safe log of every phase transition (max
2,000 missions, 40 events per mission). Both `reportPhase` callers — the registry update and the
Supabase insert — feed it, so analytics and the audit table never disagree. Because it is in
memory, the dashboard works on a fresh checkout with no database; because it is bounded, a
day-long demo does not leak.

### Tests

| Test | What it proves |
|---|---|
| `RouteMissionWorkflowTest` | stop order comes from the planner; all stops delivered; low battery compensates with the delivered count preserved; offline suspend → reconnect resumes |
| `PatrolWorkflowTest` | laps are child workflows with derived ids; `stopPatrol` ends the patrol between laps; a failed lap does not end the patrol |
| `RoutePlannerTest` | nearest-neighbour ordering is deterministic and the distance is sane |

---

## 7. Endpoint map (Week 3)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/routes` | start a multi-stop route |
| GET | `/api/routes` | routes still flying |
| GET | `/api/routes/{missionId}` | live route progress (query) |
| POST | `/api/routes/{missionId}/abort` | signal: abandon remaining stops, fly home |
| POST | `/api/routes/{missionId}/reconnect` | signal: resume after an offline suspension |
| POST | `/api/patrols` | start a patrol |
| GET | `/api/patrols` | active patrols |
| GET | `/api/patrols/{patrolId}` | patrol status |
| DELETE | `/api/patrols/{patrolId}` | signal: stop after the current lap |
| GET | `/api/analytics/summary` | fleet totals, success rate, throughput, per-drone |
| GET | `/api/analytics/timeline/{missionId}` | every phase transition for one mission |
| GET | `/api/analytics/events` | newest transitions across the fleet |
| POST | `/api/missions`, `/api/fleet/dispatch`, `/api/simulate/*` | Week 2 endpoints, unchanged |

---

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Health says *Temporal unreachable* | `docker compose up -d`; check <http://localhost:8088>. The backend retries every 5s on its own. |
| `RouteMissionWorkflow` never starts | Worker not polling yet — confirm `Temporal worker factory started` in the backend log. |
| Patrol countdown restarts from the top | You are looking at a **new** patrol; a real restart keeps the same id. Check the Web UI history for that workflow id. |
| Dashboard is empty | Backend not running, or `VITE_API_BASE` pointing somewhere else. Vite proxies to 8080 in dev. |
| Supabase writes silently skipped | `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` missing from `backend/.env`; see the log line at startup. |
| `mvn test` cannot find dependencies | Run it from `week3/backend`; the Maven wrapper/`pom.xml` lives there. |

---

## 9. What Week 4 does with this

Week 3 gives the fleet three ways to fly: one mission, many missions, many stops, forever. Week 4
puts a **supervisor** on top of them — a long-running workflow that watches the fleet, raises
incidents and intervenes automatically, plus a load-test harness for the 50-drone visual check.
See [`../week4/README.md`](../week4/README.md).
