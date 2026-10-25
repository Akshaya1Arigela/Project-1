# AeroSaga — Week 1: Temporal Foundation + 3D Scaffolding

> **Week-wise plan covered:** *"Temporal Foundation: Spin up a local Temporal cluster via Docker.
> Write the first basic Java Workflow and Activity interface for a drone 'Takeoff' command."* +
> *"3D Scaffolding: Initialize React app. Integrate CesiumJS to render a 3D Earth and static drone
> markers."*

## What's here

```
week1/
├── docker-compose.yml            Temporal dev server + Postgres + Temporal Web UI
├── temporal/dynamicconfig/       Dev-time dynamic config for the cluster
├── backend/                      Spring Boot 3.5 (Java 21) + Temporal Java SDK 1.40
│   └── src/main/java/com/aerosaga/
│       ├── AeroSagaApplication.java
│       ├── config/TemporalConfig.java       Worker registration
│       ├── domain/                          Drone, MissionCommand, MissionSnapshot, phase enum
│       ├── workflow/                        DroneMissionWorkflow + Impl  (the Takeoff saga)
│       ├── activity/                        DroneActivities + Impl      (side effects live here)
│       ├── supabase/                        Optional fleet registry over PostgREST
│       └── api/                             Mission + Drone REST endpoints
└── frontend/                     React 18 + Vite 6 + CesiumJS 1.146
    └── src/
        ├── App.jsx
        ├── lib/cesiumViewer.js   Builds the Viewer with free OSM imagery (no Ion token)
        └── components/FleetMap.jsx  Static markers from the drone registry
```

## Run it

**Terminal 1 — the cluster**
```bash
docker compose up -d
sleep 30
open http://localhost:8088     # or just browse to it
```

**Terminal 2 — the backend**
```bash
cd backend
mvn clean spring-boot:run
```

**Terminal 3 — the dashboard**
```bash
cd frontend
npm install
npm run dev
```

Then http://localhost:5173.

## The three things worth understanding in Week 1

**1. The workflow code is ordinary Java — but it is *deterministic*.**
`DroneMissionWorkflowImpl` runs inside Temporal's replay engine. It may be executed thousands of
times to reconstruct state. That means **no** `System.currentTimeMillis()`, no `new Random()`, no
network calls, no `Thread.sleep()`. Everything that touches the outside world is an **Activity**.
Use `Workflow.currentTimeMillis()` and `Workflow.random()` when you need time or randomness.

**2. The workflow does not "run" anywhere in particular.**
Your Spring Boot process is a *worker*. It polls Temporal for *tasks* (a workflow task, an activity
task), executes the code, and returns the result. Temporal — not your process — owns the state.
That is the whole reason Week 2's kill-and-restart trick works.

**3. Registration is explicit, so you can see it.**
Look at `config/TemporalConfig.java`. `WorkerFactory` → `newWorker("aero-tq")` →
`registerWorkflowImplementations(...)` + `registerActivitiesImplementations(...)`. One task queue,
one worker, one workflow type. Week 2 adds a second workflow to the same worker.

## Try it

```bash
# list the drone registry (Supabase, or in-memory demo fallback)
curl http://localhost:8080/api/drones

# start a takeoff mission
curl -X POST http://localhost:8080/api/missions \
  -H "Content-Type: application/json" \
  -d '{"droneId":"DRN-01","destinationLabel":"Secunderabad Depot","destLat":17.4375,"destLng":78.4483,"payloadKg":1.2}'

# poll the workflow's @QueryMethod — this is a query against live workflow state,
# not a database read
curl http://localhost:8080/api/missions/<missionId from the previous response>
```

Refresh http://localhost:8088 and open the `aero-tq` task queue: `DroneMissionWorkflow` runs are
visible with their status. That is your first Temporal workflow.

## Supabase (optional in Week 1)

The dashboard renders markers from `GET /api/drones`. Without Supabase the backend serves a
6-drone in-memory demo fleet so nothing is blocked. To use the real table:

1. Create a project at https://supabase.com
2. **SQL Editor** → paste [`../week2/supabase/schema.sql`](../week2/supabase/schema.sql) → **Run**
3. `cp .env.example .env` in `backend/` and fill in:
   - `SUPABASE_URL` = *Project Settings → Data API → Project URL*
   - `SUPABASE_SERVICE_ROLE_KEY` = *Project Settings → API Keys → service_role* (server-side only,
     never put this in the frontend)
