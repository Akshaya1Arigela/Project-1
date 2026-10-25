# Week 1 Writeup — AeroSaga: Temporal Foundation + 3D Scaffolding

**Project:** Temporal.io Autonomous Drone Mission Control
**Week 1 scope:** A local Temporal cluster in Docker, the first Java Workflow and Activity for a
drone "Takeoff" command, and a React + CesiumJS 3D globe with static drone markers.

---

## 1. What this week was about

The problem with a normal Java REST API is that it forgets. A request comes in, the code runs, and
if the server dies halfway through, everything in memory is gone. For a 45-minute drone flight that
is a disaster.

Week 1 sets up the two pieces that make the rest of the project possible:

1. **Temporal** — a separate server (run in Docker) that remembers exactly where every workflow
   stopped, even if our Java program is killed.
2. **A worker** — our Spring Boot program, which pulls work from Temporal, does it, and hands the
   result back.

On top of that, the frontend renders a real 3D Earth with drone markers, so we have something to
look at from day one.

---

## 2. What I built

**1. The Temporal cluster (Docker).**
`docker compose up -d` starts three containers: the Temporal server (port 7233), the Temporal Web
UI (http://localhost:8088) and PostgreSQL, which is where Temporal stores its history. Nothing in
our code talks to that database directly — that is Temporal's job.

**2. The first workflow.**
`DroneMissionWorkflowImpl.java` is plain Java with two steps: **Takeoff**, then one **Navigate**
leg to the delivery point. Between the steps it checks the battery and records the result as
`DELIVERED` or `FAILED`.

The important part is what the class does *not* do: no `Thread.sleep()`, no `new Date()`, no
network calls, no `new Random()`. Temporal may replay this code thousands of times to rebuild
state, so every step must produce the same result every time. Anything that touches the real world
is moved into an **Activity**.

**3. Activities — where the real work happens.**
`DroneActivitiesImpl.java` does the "physical" work: spool the rotors, climb, fly across the map.
An activity is allowed to sleep, take time and fail. If it fails, Temporal retries it for us — the
workflow sets 5 attempts with an increasing delay, and only after all 5 fail does the workflow see
the error.

**4. The worker registration.**
`config/TemporalConfig.java` is deliberately boring and explicit: create a `WorkerFactory`, start a
worker on task queue `aero-tq`, register the workflow class and the activity class. You can read
the whole wiring in ten lines.

**5. The REST API.**
`POST /api/missions` starts a workflow. `GET /api/missions/{id}` does **not** read a database — it
calls the workflow's `@QueryMethod`, which asks the live workflow inside Temporal "what phase are
you in right now?".

**6. The 3D dashboard.**
React + Vite + CesiumJS renders a globe using free OpenStreetMap imagery, so no Cesium Ion token is
needed. `FleetMap.jsx` places a static marker for every drone in the registry. No animation yet —
that is Week 2's job.

**7. Supabase (optional).**
`GET /api/drones` reads the drone registry from Supabase over PostgREST. If Supabase is not
configured, the backend serves a 6-drone in-memory demo fleet, so nothing is ever blocked on
credentials.

---

## 3. How to run it

Three terminals:

```bash
# 1. Temporal cluster
cd week1 && docker compose up -d        # wait ~30 s for the first boot

# 2. Backend
cd week1/backend && mvn clean spring-boot:run

# 3. Dashboard
cd week1/frontend && npm install && npm run dev
```

Open http://localhost:5173 for the globe and http://localhost:8088 for the Temporal UI.

Start a mission:

```bash
curl -X POST http://localhost:8080/api/missions \
  -H "Content-Type: application/json" \
  -d '{"droneId":"DRN-01","destinationLabel":"Secunderabad Depot","destLat":17.4375,"destLng":78.4483,"payloadKg":1.2}'
```

Then refresh the Temporal UI and open the `aero-tq` task queue — the `DroneMissionWorkflow` run is
there with its status.

---

## 4. The three ideas worth understanding

**1. The workflow code is ordinary Java, but it must be deterministic.**
It runs inside Temporal's replay engine. Use `Workflow.currentTimeMillis()` instead of the system
clock, `Workflow.random()` instead of `new Random()`, and put everything else in an activity.
Breaking this rule is the number one cause of "non-deterministic workflow" errors.

**2. The workflow does not run "somewhere".**
Our Spring Boot process is only a **worker**: it polls Temporal for tasks, executes them and
returns the result. Temporal — not our JVM — owns the state. This is the whole reason the
kill-and-restart test in Week 2 works.

**3. Reading state is a query, not a database read.**
`@QueryMethod snapshot()` asks the running workflow directly. There is no `mission` table to keep
in sync, because the workflow object *is* the truth.

---

## 5. What was tested and the results

| Test | Result |
|---|---|
| Temporal cluster via Docker | 3 containers up, Web UI shows the `default` namespace **Connected** |
| Backend builds | `mvn package` succeeds |
| First workflow run | `POST /api/missions` → run visible on task queue `aero-tq` in the Web UI |
| Takeoff → Navigate | Mission moved `TAKEOFF` → `NAVIGATING` → `DELIVERED` (45 s of animated flight) |
| Live query | Repeated `GET /api/missions/{id}` returned changing `phase`, `step`, battery and position from `@QueryMethod` |
| Dashboard build | `vite build` succeeds; globe renders with 6 static markers and no Cesium Ion token |
| Supabase fallback | With no `.env`, `/api/drones` still returns the demo fleet instead of failing |
| Backend build | `mvn package` succeeds with the `.env` loader added |

**Designed but not exercised this week:** the low-battery branch (`FAILED` with *"Battery … is
below the … threshold"*) and the 5-attempt activity retry policy are both in the Week 1 code, but I
ran the normal flight path rather than forcing a failure. Week 2's tests cover failure paths
properly.

**Honest limitation:** Week 1 markers are static by design — there is no telemetry stream yet, so
"smooth rendering of 50 moving markers" is a Week 2 check, not a Week 1 result.

---

## 6. What I learned

1. **State belongs in the workflow, not in the process.** The workflow's fields are serialised into
   Temporal's history after every step. Kill the JVM and those exact values come back.
2. **Determinism is a discipline, not a suggestion.** Half of writing workflow code is knowing what
   you are *not* allowed to call.
3. **The right exception type matters.** An activity stub throws `io.temporal.failure.ActivityFailure`.
   Catching a similar-looking type compiles fine and silently never matches — a bug that only shows
   up at runtime.
4. **A 3D map is just another view of the API.** CesiumJS renders markers; the data still comes
   from one JSON endpoint, which is what makes Week 2's animation a small change rather than a
   rewrite.
