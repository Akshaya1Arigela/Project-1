# AeroSaga — Temporal.io Autonomous Drone Mission Control

Two self-contained weekly drops:

| Folder | What it contains | Week-wise plan coverage |
|---|---|---|
| [`week1/`](week1/) | Temporal cluster (Docker) + first Java Workflow & Activity (`Takeoff`) + React/CesiumJS 3D globe with **static** drone markers | Week 1 |
| [`week2/`](week2/) | Full mission **saga** with compensation, `Promise.allOf` fleet fan-out, query/signal APIs, Spring Boot **WebSocket** telemetry gateway, animated 50-drone CesiumJS dashboard, **Supabase** fleet + mission history | Week 2 + Mid-Project Review resiliency audit |

`week2/` is a **superset** of `week1/` — it runs standalone, you do not need to copy anything forward.

> **Supabase choice:** Temporal keeps its own durable execution state in its own database.
> Supabase is used for the *application* data the spec needs: the drone registry, mission
> records, and an audit trail of workflow state transitions / resume events.

---

## 0. Prerequisites — install these first

| Tool | Version | Check with |
|---|---|---|
| **JDK** | 21+ | `java -version` |
| **Maven** | 3.9+ | `mvn -version` |
| **Docker Desktop** | any recent | `docker -v` and `docker compose version` |
| **Node.js** | 20.19+ (or 22/24) | `node -v` |
| **Git** | any | `git -v` |

### Installing Maven

**Windows (winget):**
```powershell
winget install --id Apache.Maven -e
```
**Windows (manual):** download https://maven.apache.org/download.cgi → unzip to `C:\tools\maven`
→ add `C:\tools\maven\bin` to your **PATH** → also set `JAVA_HOME=C:\Program Files\Java\jdk-21`.

**macOS (Homebrew):**
```bash
brew install maven
```

**Ubuntu / Debian:**
```bash
sudo apt update && sudo apt install -y maven
```

> After installing, **open a brand-new terminal** — PATH changes only apply to new shells.
> Verify with `mvn -version`. It must print the JDK path, otherwise set `JAVA_HOME`.

### Installing Docker

Download **Docker Desktop** from https://www.docker.com/products/docker-desktop/ and start it
(the whale icon must be in the system tray / menu bar). Then verify:

```bash
docker compose version    # must print Docker Compose version v2.x
```

> **Windows:** Docker Desktop needs WSL 2. If `docker compose version` fails, open
> *Windows Features* → enable *Windows Subsystem for Linux* and *Virtual Machine Platform*,
> reboot, then retry.
>
> **RAM:** the Temporal dev cluster + Postgres want ~2 GB. Give Docker Desktop at least 4 GB
> in *Settings → Resources*.

---

## 1. Run Week 1

### 1a. Start the Temporal cluster

```bash
cd week1
docker compose up -d
docker compose ps          # all 3 services should be "running" / "Up"
```

This starts:

| Service | Port | URL |
|---|---|---|
| Temporal Frontend (gRPC) | `7233` | used by the Java backend |
| Temporal Web UI | `8088` | http://localhost:8088 |
| PostgreSQL 16 | `5432` | internal persistence |

Wait ~30 seconds for the first boot (schema setup), then open http://localhost:8088 — you should
see the `default` namespace listed as **"Connected"**.

### 1b. Start the Java backend

```bash
cd week1/backend
mvn clean spring-boot:run
```

Wait for `Started AeroSagaApplication`. You should see a Temporal worker log line mentioning task
queue `aero-tq`.

### 1c. (Optional) Point it at Supabase

Skip this for now and the backend falls back to an in-memory demo fleet — the app still works.

1. Create a free project at https://supabase.com
2. Open **SQL Editor** → paste the contents of [`week2/supabase/schema.sql`](week2/supabase/schema.sql) → **Run**.
   (Run it from `week1` too; the schema is identical and shared.)
3. Copy `.env.example` → `.env` in `week1/backend/` and fill in the two values.

### 1d. Start the 3D dashboard

```bash
cd week1/frontend
npm install
npm run dev
```

Open **http://localhost:5173**. You get a real 3D globe (free OpenStreetMap imagery, **no Cesium
Ion token needed**) with the static drone markers read from the drone registry.

> First `npm install` downloads ~5 MB of Cesium assets — it is normal for it to take a minute.

### 1e. Launch a mission

```bash
curl -X POST http://localhost:8080/api/missions \
  -H "Content-Type: application/json" \
  -d '{"droneId":"DRN-01","destinationLabel":"Secunderabad Depot","destLat":17.4375,"destLng":78.4483,"payloadKg":1.2}'
```

Then watch the run appear under **Workflows → aero-tq** in http://localhost:8088.

---

## 2. Run Week 2

```bash
cd week2
docker compose up -d          # or reuse the week1 cluster if it is already running
cd backend && mvn clean spring-boot:run
cd ../frontend && npm install && npm run dev
```

Open **http://localhost:5173**. The dashboard now animates **50 drones in real time** over
WebSockets, shows each drone's live Temporal workflow phase, and lets you run the storm scenario
from the problem statement.

### The storm / resiliency demo (the money shot)

1. Click **Dispatch Fleet** → several drones start real `DroneMissionWorkflow` runs.
2. Click **Simulate Storm** → drones entering the storm corridor lose their link.
3. Watch the workflow phase flip to **`OFFLINE_SUSPENDED`**. The workflow is now *durably* parked
   inside Temporal — nothing is polling, nothing is timing out.
4. **Wait.** Two minutes or two hours, it does not matter. Temporal holds the exact state.
5. Click **Reconnect** → the workflow wakes up, re-plans from the last known position, flies the
   drone home and the dashboard updates itself.

### The 10-minute kill-and-restart proof (Mid-Project Review)

1. Dispatch a mission that will take ~10 minutes (`mvn spring-boot:run` in one terminal).
2. Halfway through, press **Ctrl+C** on the backend. The Java process is **gone**.
3. Confirm in http://localhost:8088 that the workflow is still `Running` — Temporal owns the state, not the JVM.
4. Restart with `mvn spring-boot:run`.
5. The worker re-attaches automatically and the workflow **resumes on the exact activity it died on**. No database polling, no recovery script.

Full narrative with expected log lines: [`week2/README.md`](week2/README.md).

---

## 3. Shutting down

```bash
cd week1        # or week2
docker compose down          # keeps data
docker compose down -v       # DELETES the Temporal database — fresh start
```

Frontend and backend are just Ctrl+C.

---

## 4. Repo layout

```
.
├── README.md                  ← you are here (setup + step-by-step)
├── week1/
│   ├── README.md
│   ├── docker-compose.yml     Temporal dev cluster
│   ├── backend/               Spring Boot + Temporal SDK
│   └── frontend/              React + Vite + CesiumJS (static markers)
└── week2/
    ├── README.md              ← includes the resiliency audit walkthrough
    ├── docker-compose.yml
    ├── supabase/schema.sql    ← paste into the Supabase SQL editor
    ├── backend/               Spring Boot + Temporal saga + WebSockets
    └── frontend/              React + CesiumJS + live telemetry
```
