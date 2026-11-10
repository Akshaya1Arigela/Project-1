package com.aerosaga.supervisor;

import com.aerosaga.domain.Incident;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The supervisor's incident feed, kept in memory.
 *
 * <p>Why not just read Supabase? Because the supervisor has to work on a fresh checkout with no
 * database configured, and because the dashboard should show an incident three seconds after it
 * happens — not after a round trip to Singapore. Supabase remains the durable copy; this is the
 * live one.</p>
 *
 * <p>Bounded: a supervisor scanning every ten seconds for a day would otherwise grow forever.</p>
 */
@Component
public class IncidentLog {

    private static final int MAX_INCIDENTS = 500;

    private final Deque<Incident> incidents = new ConcurrentLinkedDeque<>();
    private final AtomicLong total = new AtomicLong();

    /** Append one incident. Called from an activity, so it must be thread-safe and cheap. */
    public void record(Incident incident) {
        if (incident == null) {
            return;
        }
        incidents.addFirst(incident);
        total.incrementAndGet();
        while (incidents.size() > MAX_INCIDENTS) {
            incidents.pollLast();
        }
    }

    /** Newest first. */
    public List<Incident> recent(int limit) {
        List<Incident> all = new ArrayList<>(incidents);
        all.sort(Comparator.comparingLong(Incident::at).reversed());
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    public int size() {
        return incidents.size();
    }

    public long total() {
        return total.get();
    }

    public void clear() {
        incidents.clear();
    }
}
