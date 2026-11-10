package com.aerosaga.analytics;

import com.aerosaga.domain.MissionPhase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded, in-memory log of every mission phase transition this process has seen.
 *
 * <h2>Why this exists next to Supabase</h2>
 * <p>Supabase is the durable audit trail, but the dashboard has to work on a fresh checkout with
 * no database configured. The ledger is the same information, kept in memory, so analytics is
 * never "empty because you did not set up a database". When Supabase <em>is</em> configured the
 * two agree, because both are fed by the same {@code reportPhase} call.</p>
 *
 * <h2>Bounded on purpose</h2>
 * <p>A demo that runs for a day would otherwise leak. Old missions are evicted once the cap is
 * passed, so memory stays flat no matter how many missions you dispatch.</p>
 */
@Component
public class MissionLedger {

    /** Enough for a long demo, small enough that nobody notices it in a heap dump. */
    private static final int MAX_MISSIONS = 2_000;
    private static final int MAX_EVENTS_PER_MISSION = 40;

    /**
     * One recorded transition.
     *
     * @param missionId mission this belongs to
     * @param droneId   airframe
     * @param phase     phase the mission entered
     * @param detail    human-readable note, straight from the workflow
     * @param at        epoch millis, taken from the activity (never from the workflow's clock)
     */
    public record Event(String missionId, String droneId, MissionPhase phase, String detail, long at) {
    }

    private final Map<String, Deque<Event>> byMission = new ConcurrentHashMap<>();
    private final Deque<String> order = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalRecorded = new AtomicLong();

    /** Append a transition. Cheap, thread-safe, and safe to call from a retried activity. */
    public void record(String missionId, String droneId, MissionPhase phase, String detail) {
        if (missionId == null || missionId.isBlank()) {
            return;
        }
        Deque<Event> events = byMission.computeIfAbsent(missionId, id -> {
            order.addLast(id);
            return new ConcurrentLinkedDeque<>();
        });
        events.addLast(new Event(missionId, droneId, phase, detail, System.currentTimeMillis()));
        while (events.size() > MAX_EVENTS_PER_MISSION) {
            events.pollFirst();
        }
        totalRecorded.incrementAndGet();
        evictOldMissions();
    }

    /** Full transition history for one mission, oldest first — the timeline view. */
    public List<Event> timeline(String missionId) {
        Deque<Event> events = byMission.get(missionId);
        return events == null ? List.of() : new ArrayList<>(events);
    }

    /** Every event we still hold, newest first. */
    public List<Event> recent(int limit) {
        List<Event> all = new ArrayList<>();
        byMission.values().forEach(all::addAll);
        all.sort(Comparator.comparingLong(Event::at).reversed());
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /** Latest event per mission — what analytics actually aggregates. */
    public List<Event> latestPerMission() {
        List<Event> latest = new ArrayList<>();
        byMission.forEach((missionId, events) -> {
            Event last = events.peekLast();
            if (last != null) {
                latest.add(last);
            }
        });
        return latest;
    }

    /** First event per mission, used to measure duration from start to finish. */
    public Map<String, Long> firstSeen() {
        Map<String, Long> first = new ConcurrentHashMap<>();
        byMission.forEach((missionId, events) -> {
            Event head = events.peekFirst();
            if (head != null) {
                first.put(missionId, head.at());
            }
        });
        return first;
    }

    public int missionCount() {
        return byMission.size();
    }

    public long totalRecorded() {
        return totalRecorded.get();
    }

    public void clear() {
        byMission.clear();
        order.clear();
    }

    private void evictOldMissions() {
        while (byMission.size() > MAX_MISSIONS) {
            String oldest = order.pollFirst();
            if (oldest == null) {
                return;
            }
            byMission.remove(oldest);
        }
    }
}
