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

    public void put(String missionId, MissionCommand command) {
        commands.put(missionId, command);
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
