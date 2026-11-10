# Week 4 Writeup — AeroSaga: The Supervisor That Watches the Fleet

**Project:** Temporal.io Autonomous Drone Mission Control
**Week 4 scope:** a long-running supervisor workflow, incident detection, automatic intervention,
load-test and readiness drills, and the `incidents` table in Supabase.

---

## 1. What this week was about

Week 3 ended with a fleet that can do almost anything: single missions, fleets, multi-stop routes,
repeating patrols.

But every one of those things needs a **person watching**. If a drone goes silent during a storm
and nobody is looking at the dashboard, it just sits there — suspended, safe, and forgotten.

Week 4 adds the person who never blinks: a **supervisor workflow**. It looks at the fleet every few
seconds, notices when an airframe has been silent too long while flying, writes an incident, and —
if you let it — tells that mission to abort and come home.

The second half of the week is about proving things on review day: one endpoint that dispatches 50
real missions at once, and one endpoint that answers "is this demo actually wired up?".

---

## 2. What I built

**1. The supervisor workflow.**
`FleetSupervisorWorkflowImpl.java` is a workflow with a loop instead of a sequence. Each pass it
asks an activity for a snapshot of the fleet, looks for problems, and then parks on a durable timer
until the next pass. It never returns on its own — you stop it with a signal.

**2. Incidents, raised once.**
A drone that has been silent for longer than the grace window (default 20 s) gets a `LINK_LOST`
incident. The supervisor remembers which missions it already reported, so a two-hour storm is one
incident, not seven hundred. The moment the link comes back, it forgets — so a *second* outage is a
*second* incident.

**3. Intervention that isn't a remote control.**
When auto-intervene is on, the supervisor calls an activity that sends the mission the **same
`abort` signal the dashboard sends**. The mission then does what it already knows how to do:
compensate and fly home. The supervisor decides *when*; the mission knows *how*. Keeping that
boundary is what stops the supervisor from growing into a second flight controller.

**4. Two ways to read it, one way to change it.**
The dashboard reads the supervisor through a **query** (counters, fleet view, recent incidents) and
changes it through **signals** (`stop`, `setAutoIntervene`). No restart, no config file reload, no
shared mutable state.

**5. Continue-as-new.**
A supervisor scanning every ten seconds for a week is about 60,000 events. Every 200 scans it hands
its totals to a fresh run and closes the current event history: same workflow id, same counters,
small history. This is the first workflow in the project that is meant to run forever, and this is
how that is done properly.

**6. Kill it and it comes back.**
The supervisor is a workflow, so it is restored exactly like missions, routes and patrols were:
after a restart the backend asks Temporal "which supervisors are running?" and re-attaches.

**7. Supabase incidents table.**
`schema.sql` gained `incidents`, written by the supervisor's `recordIncident` activity (through the
backend, so the service-role key stays server-side). The dashboard reads the in-memory feed instead,
so everything works before Supabase is configured.

**8. Review drills as endpoints.**
`POST /api/drill/load-test` dispatches N real missions in one burst — the "50+ moving markers"
visual check. `GET /api/drill/readiness` returns three lights: Temporal polling, Supabase
configured, fleet registered.

**9. Five new tests.**
They start the real supervisor, wait for real scans, send the real stop signal and read the final
report. They prove: one incident per outage, no duplicate interventions, observer mode sends
nothing, a healthy fleet stays quiet, and a failed scan is itself an incident. Total suite: 13.

---

## 3. Why the supervisor is a workflow and not a `@Scheduled` job

This is the question I expect in a review, so here is the short answer.

A `@Scheduled` method in Spring is a timer **in one JVM**. It is not durable: restart the process
and the schedule resets; run two copies and you get two supervisors; and it has no memory of what it
already reported.

A supervisor workflow is a timer **in Temporal's database**. It survives restarts, there is exactly
one of it per workflow id, its dedupe memory is part of its durable state, and every decision it
made is in an event history you can read a month later. For a watcher that is supposed to run
forever, that difference is the whole feature.

---

## 4. How to show it in a review

**Storm → incident → recovery (2 minutes).**
Start the supervisor with grace 5 s. Dispatch a mission. Press **Simulate storm**. Within about ten
seconds the incident feed shows `LINK_LOST`, with `→ sent home`. The mission's card turns to
`COMPENSATED`; the drone flies home. Open the mission in the Temporal Web UI and point at the
`WorkflowExecutionSignaled` event — that is the supervisor's intervention, durably recorded.

**Observer mode (30 seconds).**
Flip the supervisor to observe-only. Trigger the storm again. The incident appears with action
`NONE`, and nothing is sent. This is the version a safety review usually prefers.

**Kill the supervisor (1 minute).**
Stop the backend mid-run, start it again, and show the supervisor still listed with the same id and
counters. Nothing was in a thread; it was in the database.

**Load test (1 minute).**
Press **Dispatch 50 drones**. Fifty markers start moving and the globe stays smooth. Each one is a
real workflow — open the Web UI and count them.

---

## 5. Problems I hit (and what they taught me)

1. **The first version raised an incident every scan.** Three hundred incidents for one storm made
   the feed useless. The fix was dedupe keyed on mission id, cleared when the link returns — which
   also gave "second outage = second incident" for free.

2. **Nightly battery warnings would have flooded the feed.** Same fix, different set: warn once per
   mission, not once per scan.

3. **The supervisor cannot use the mission service directly.** Workflow code may not touch Spring or
   sockets. Everything goes through activities: scan, record, intervene. That constraint is what
   makes the replay test possible.

4. **A failed scan is not nothing.** My first version just logged it and moved on. But a supervisor
   that has gone blind is exactly what an operator needs to know about, so a failed scan now raises
   a `SCAN_FAILED` incident and the report status becomes `DEGRADED`.

5. **Load test with no cluster looked like a hang.** Fifty starts, each waiting out an RPC timeout,
   is six minutes of nothing. It now checks that the worker is polling and returns 503 immediately
   with a real explanation.

6. **A test asserted the wrong thing.** I checked the final report status was `DEGRADED`, but the
   final report always says `STOPPED` — that is what a stop signal produces. The test now asserts
   on the incident that proves blindness, not on a status that is overwritten by design.

---

## 6. What is still simple (on purpose)

- The fleet is simulated; `scanFleet` reads the telemetry engine. A real implementation reads
  MQTT/ADS-B and is still just an activity.
- The only intervention is "come home". Sending a different drone to finish the delivery is a
  different workflow and a routing decision, not a supervisor tweak.
- Incidents in Supabase are best-effort writes; the durable copy is Temporal's history.
- Detection latency is `scan interval + grace` by construction. That is a policy choice, and the
  first thing to tune for a real fleet.

---

## 7. Where the project ends up

Five workflow types on one task queue, all durable:

| Workflow | Job |
|---|---|
| `DroneMissionWorkflow` | one parcel, one destination, with compensation |
| `FleetMissionWorkflow` | N missions at once behind `Promise.allOf` |
| `RouteMissionWorkflow` | one flight, many stops, optimised order |
| `PatrolWorkflow` | recurring laps on durable timers |
| `FleetSupervisorWorkflow` | watches all of the above and intervenes |

The 3D globe shows the fleet, the analytics card shows the numbers, the history panel shows the
Supabase audit trail, and the supervisor card shows what the system decided on its own — which is
the part of the problem statement that no amount of REST could have delivered.
