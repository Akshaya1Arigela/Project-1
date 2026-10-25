import { useEffect, useMemo, useState } from 'react';
import FleetMap from './components/FleetMap.jsx';
import MissionPanel from './components/MissionPanel.jsx';
import ControlPanel from './components/ControlPanel.jsx';
import HistoryPanel from './components/HistoryPanel.jsx';
import { useFleetTelemetry } from './hooks/useFleetTelemetry.js';
import { api } from './lib/api.js';

/**
 * Storm corridor, mirrored from `aerosaga.telemetry.storm` in backend/application.yml.
 *
 * The backend does not expose it over REST yet, so the frontend keeps the same constants to draw
 * the red rectangle. Change one and change the other.
 */
const STORM_BOUNDS = { west: 78.42, south: 17.33, east: 78.56, north: 17.44 };

export default function App() {
  const { drones, homeBase, storm, connected, trails } = useFleetTelemetry();
  const [selectedDrone, setSelectedDrone] = useState(null);
  const [selectedMission, setSelectedMission] = useState(null);
  const [health, setHealth] = useState(null);
  const [historyVersion, setHistoryVersion] = useState(0);

  useEffect(() => {
    let cancelled = false;
    async function poll() {
      try {
        const result = await api.health();
        if (!cancelled) setHealth(result);
      } catch {
        if (!cancelled) setHealth(null);
      }
    }
    poll();
    const timer = setInterval(poll, 5000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  const fleet = useMemo(
    () => ({
      total: drones.length,
      flying: drones.filter((d) => d.missionId).length,
      suspended: drones.filter((d) => d.phase === 'OFFLINE_SUSPENDED' || d.linkUp === false).length,
    }),
    [drones],
  );

  const selected = drones.find((d) => d.id === selectedDrone) ?? null;
  const bumpHistory = () => setHistoryVersion((v) => v + 1);

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">🚁</span>
          <div>
            <h1>AeroSaga</h1>
            <p>Temporal.io Autonomous Drone Mission Control · Week 2</p>
          </div>
        </div>

        <div className="topbar-stats">
          <Stat label="Fleet" value={fleet.total} />
          <Stat label="On mission" value={fleet.flying} tone="ok" />
          <Stat label="Link lost" value={fleet.suspended} tone={fleet.suspended ? 'bad' : ''} />
          <Stat label="Telemetry" value={connected ? 'live' : 'reconnecting…'} tone={connected ? 'ok' : 'warn'} />
          <Stat
            label="Supabase"
            value={health?.supabase === 'connected' ? 'connected' : 'demo data'}
            tone={health?.supabase === 'connected' ? 'ok' : 'warn'}
          />
          <Stat label="Backend" value={health ? 'online' : 'offline'} tone={health ? 'ok' : 'bad'} />
        </div>
      </header>

      <main className="layout">
        <FleetMap
          drones={drones}
          trails={trails}
          homeBase={homeBase}
          storm={storm}
          stormBounds={STORM_BOUNDS}
          onDroneSelect={setSelectedDrone}
        />

        <aside className="sidebar">
          {!health && (
            <section className="card card-error">
              <h2>Backend unreachable</h2>
              <p>
                Start it with <code>cd week2/backend &amp;&amp; mvn clean spring-boot:run</code>
              </p>
              <p className="hint">The globe keeps running on whatever it last received.</p>
            </section>
          )}

          <ControlPanel storm={storm} homeBase={homeBase} onActivity={bumpHistory} />

          {selected && (
            <section className="card">
              <h2>{selected.callsign}</h2>
              <dl className="kv">
                <dt>Drone</dt>
                <dd>{selected.id}</dd>
                <dt>Status</dt>
                <dd>{selected.status}</dd>
                <dt>Workflow phase</dt>
                <dd>{selected.phase}</dd>
                <dt>Mission</dt>
                <dd>{selected.missionId ?? '—'}</dd>
                <dt>Battery</dt>
                <dd>{Math.round(selected.batteryPct)}%</dd>
                <dt>Altitude</dt>
                <dd>{Math.round(selected.altitudeM)} m</dd>
                <dt>Radio link</dt>
                <dd className={selected.linkUp ? 'tone-ok' : 'tone-bad'}>
                  {selected.linkUp ? 'UP' : 'DOWN'}
                </dd>
                <dt>Position</dt>
                <dd>
                  {selected.lat.toFixed(4)}, {selected.lng.toFixed(4)}
                </dd>
              </dl>
            </section>
          )}

          <MissionPanel
            selectedDroneId={selectedMission}
            onSelectMission={setSelectedMission}
            version={historyVersion}
          />

          <HistoryPanel />
        </aside>
      </main>
    </div>
  );
}

function Stat({ label, value, tone }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className={`stat-value ${tone ? `tone-${tone}` : ''}`}>{value}</span>
    </div>
  );
}
