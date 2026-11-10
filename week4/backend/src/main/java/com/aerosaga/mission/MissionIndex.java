package com.aerosaga.mission;

import com.aerosaga.domain.MissionCommand;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An index of every mission this process has started or restored.
 *
 * <p>Temporal owns the workflow state; this owns only the <em>command</em> that a mission id maps
 * back to, which is what the Supabase audit writer needs in order to attach a phase transition to
 * the right row. It is a cache, not a source of truth — losing it costs you nothing.</p>
 */
@Component
public class MissionIndex {

    private final Map<String, MissionCommand> commands = new ConcurrentHashMap<>();

    /**
     * WEEK 4: mission id → Temporal workflow id.
     *
     * <p>Needed by the fleet supervisor, which intervenes by <em>signalling</em> a mission it can
     * only name by mission id. Deriving the workflow id from a string prefix would work today and
     * break the first time a workflow is renamed, so the producer records it instead.</p>
     */
    private final Map<String, String> workflowIds = new ConcurrentHashMap<>();

    public void put(String missionId, MissionCommand command) {
        commands.put(missionId, command);
    }

    public void put(String missionId, MissionCommand command, String workflowId) {
        commands.put(missionId, command);
        if (workflowId != null && !workflowId.isBlank()) {
            workflowIds.put(missionId, workflowId);
        }
    }

    /** The workflow that owns this mission, or {@code null} if this process never started it. */
    public String workflowId(String missionId) {
        return workflowIds.get(missionId);
    }

    public MissionCommand get(String missionId) {
        return commands.get(missionId);
    }

    public boolean contains(String missionId) {
        return commands.containsKey(missionId);
    }

    public int size() {
        return commands.size();
    }
}
