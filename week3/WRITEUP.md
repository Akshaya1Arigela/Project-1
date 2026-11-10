# Week 3 Writeup — AeroSaga: Multi-Stop Routes, Patrols and Fleet Numbers

**Project:** Temporal.io Autonomous Drone Mission Control
**Week 3 scope:** more than one parcel per flight, repeating patrols that survive a restart, and a
fleet dashboard that shows real numbers instead of only dots.

---

## 1. What this week was about

Week 2 could fly one parcel to one address, and could pause when a drone lost its radio.

That is a good demo, but a delivery company does not work like that:

- one drone carries **four parcels** on one battery,
- the same **route repeats** every evening,
- and the manager asks **"how many deliveries today, and how many did we have to cancel?"**

Week 3 is those three things. Same Temporal cluster, same Spring Boot app, same 3D map — three new
pieces of work on top.

---

## 2. What I built

**1. A route workflow (many stops, one flight).**
`RouteMissionWorkflowImpl.java` takes a list of stops. It calls a `planRoute` **activity** to put
them in a sensible order, takes off **once**, flies stop → stop dropping each parcel, and comes home
**once**. Compare that with Week 2, where every parcel needed its own takeoff and landing.

**2. Compensation for a route.**
If the battery falls below 25% after the second of four stops, the drone must not try the last two.
The workflow stops the trip, keeps the parcels that were already delivered, returns the rest to
inventory, and flies home. The result says honestly: *"delivered 2 of 4, returned to base."*
This is the "return to base on low battery" rule from the problem statement, now applied to a real
multi-stop saga.

**3. Patrolling, without cron.**
`PatrolWorkflowImpl.java` flies the same circuit every 30 seconds for three laps. The bit that
matters is what happens **between** laps: the workflow calls `Workflow.await(...)`, which parks it
inside Temporal. There is no sleeping thread, no `@Scheduled` method, no Quartz table. If I kill the
Spring Boot process mid-countdown, the countdown is still in Temporal's history, so when the backend
comes back the patrol continues on the lap it was on.

**4. Routes survive a restart too.**
Both new workflow types re-attach after a restart: the backend asks Temporal *"which route and
patrol workflows are still running?"* and keeps a handle to each one. This is the same visibility
query Week 2 used for missions — the pattern is about Temporal, not about one workflow class.

**5. Fleet analytics.**
Every phase change (takeoff, navigating, delivered, compensated, offline…) is written into a small
in-memory ledger as well as Supabase. `GET /api/analytics/summary` folds that ledger into totals:
delivered, compensated, failed, success rate, average flight time, how many suspensions happened,
a per-drone table, and a five-minute bar chart of throughput. A per-mission timeline endpoint shows
exactly when a mission paused and when it resumed.

**6. Three new dashboard cards.**
*Multi-stop route* (dispatch + live stops delivered + flown order), *Patrols* (start, countdown,
stop), and *Fleet analytics* (the numbers above). The 3D map and mission list are unchanged.

**7. Tests.**
Eight tests run in Temporal's in-process test environment — no Docker needed. They check the stop
order really comes from the planner, low battery compensates with the delivered count preserved, a
suspended route resumes after the reconnect signal, patrol laps are separate child workflows, and a
failed lap does not kill the patrol.

---

## 3. Why the stop order is an activity, not a method

This is the one design decision worth explaining in a review.

Sorting four stops looks like a private helper. But if the workflow sorts them itself, and later the
sorting code changes (new traffic data, a no-fly zone), then a **replay** of an old mission could
produce a *different* order. Temporal would see the workflow do something it did not do the first
time, and it would refuse to continue with a non-determinism error.

Making it an activity means the answer is **stored in the event history**. The workflow reads the
stored list. Old missions keep the old route forever, new missions get the new one.

Rule of thumb used throughout this project: *anything that could give a different answer tomorrow
belongs in an activity.*

---

## 4. How to show it in a review

**Route demo (about 2 minutes).** Press **Fly 4-stop route**. Watch the card: the stops were sent in
a shuffled order, and the card prints the order that was actually flown. Then open the workflow in
the Temporal Web UI at <http://localhost:8088> and point at the `planRoute` activity result sitting
in the history — that is the proof the optimisation is durable.

**Patrol demo (about 1 minute).** Start a patrol on a 30-second interval with 3 laps. While it says
*next in 30s*, stop the backend with `Ctrl+C`. Start it again. The patrol reappears with the same lap
count and the remaining countdown. No database polling, no cron restart, nothing to clean up.

**Numbers demo.** Dispatch a few missions, abort one, let one run low on battery, trigger a storm.
The analytics card shows delivered vs compensated vs failed, and the suspension count goes up. Click
through to `/api/analytics/timeline/<missionId>` to show where the pause happened.

---

## 5. Problems I hit (and what they taught me)

1. **`Promise.allOf` was the wrong shape for routes.** Fan-out is for *independent* drones. One
   drone visiting four stops is a **sequence** with a shared battery — so it is a loop inside one
   workflow, not children. Reusing the Week 2 parent would have made partial delivery impossible to
   express.

2. **A patrol is not a loop in a `while(true)` thread.** My first instinct was a scheduler thread.
   That breaks the whole promise of the project: kill the JVM and the schedule dies. The workflow
   loop with a durable timer is the same code shape but a completely different guarantee.

3. **Derived ids, not random ones.** Child missions are named `patrolId-c1`, `-c2`, … If they were
   random, a restart could replay the same lap under a new id and deliver the same parcel twice.

4. **Analytics kept saying "zero" when Supabase was not configured.** That is why the ledger exists:
   the dashboard must show something true on a fresh checkout. Supabase is the durable audit trail;
   the ledger is the live view, and both are fed by the same `reportPhase` call.

5. **The route card initially showed the stops in the order the UI sent them.** That quietly hid the
   feature. I now print the planner's order explicitly, and the test asserts it differs from the
   input order when the input is deliberately shuffled.

---

## 6. What is still simple (on purpose)

- Route optimisation is nearest-neighbour on a flat Earth. Good enough to be visibly different from
  the input order, small enough to test. Swapping in a real routing service means changing one
  activity.
- Patrols are finite (`cycles`), so they can complete and be shown in the Web UI as `COMPLETED`.
- Analytics lives in memory. It resets when the backend restarts; Supabase still holds the history,
  and a Week 4 supervisor can rebuild totals from it.
- The ledger caps at 2,000 missions. Beyond that it evicts oldest-first — flat memory, honest limits.

---

## 7. What Week 4 adds

Week 3 lets the fleet fly in three different ways. Week 4 puts a **supervisor** above all of them: a
long-running workflow that watches the fleet, notices a drone that has been offline too long, raises
an incident, intervenes by signalling the stranded mission to come home, and keeps a durable record
of what it did. Plus a load-test harness for the 50-marker visual check from the review plan.

See [`WRITEUP.md` in week 4](../week4/WRITEUP.md).
