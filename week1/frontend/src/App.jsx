import { useCallback, useEffect, useState } from 'react';
import FleetMap from './components/FleetMap.jsx';
import { api } from './lib/api.js';

const POLL_MS = 4000;

export default function App() {
  const [drones, setDrones] = useState([]);
  const [homeBase, setHomeBase] = useState(null);
  const [health, setHealth] = useState(null);
  const [missions, setMissions] = useState([]);
  const [error, setError] = useState(null);
  const [selected, setSelected] = useState(null);

  const refresh = useCallback(async () => {
    try {
      const [fleet, state] = await Promise.all([api.drones(), api.fleetState()]);
      setDrones(fleet);
      setHomeBase(state.homeBase);
      setMissions(state.missions ?? []);
      setHealth(await api.health());
      setError(null);
    } catch (e) {
      // Week 1: this is almost always "the backend is not running yet".
      setError(e.message);
    }
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, POLL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  const selectedDrone = drones.find((d) => d.id === selected) ?? null;

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">🚁</span>
          <div>
            <h1>AeroSaga</h1>
            <p>Temporal.io Autonomous Drone Mission Control · Week 1</p>
          </div>
        </div>
        <div className="topbar-stats">
          <Stat label="Fleet" value={drones.length} />
          <Stat label="Active missions" value={missions.length} />
          <Stat
            label="Supabase"
            value={health?.supabase === 'connected' ? 'connected' : 'demo data'}
            tone={health?.supabase === 'connected' ? 'ok' : 'warn'}
          />
          <Stat
            label="Backend"
            value={error ? 'offline' : 'connected'}
            tone={error ? 'bad' : 'ok'}
          />
        </div>
      </header>

      <main className="layout">
        <FleetMap drones={drones} homeBase={homeBase} onDroneSelect={setSelected} />

        <aside className="sidebar">
          {error && (
            <section className="card card-error">
              <h2>Backend unreachable</h2>
              <p>
                Start it with <code>cd week1/backend &amp;&amp; mvn clean spring-boot:run</code>
              </p>
              <p className="muted">{error}</p>
              <button onClick={refresh}>Retry</button>
            </section>
          )}

          <section className="card">
            <h2>Drone registry</h2>
            <p className="muted">
              {health?.supabase === 'connected'
                ? 'Live from Supabase via PostgREST.'
                : 'In-memory demo fleet — Supabase not configured.'}
            </p>
            <ul className="drone-list">
              {drones.map((d) => (
                <li
                  key={d.id}
                  className={selected === d.id ? 'selected' : ''}
                  onClick={() => setSelected(d.id)}
                >
                  <span className={`dot dot-${d.status}`} />
                  <span className="callsign">{d.callsign}</span>
                  <span className="muted">{d.id}</span>
                  <span className="battery">{Math.round(d.batteryPct)}%</span>
                </li>
              ))}
              {drones.length === 0 && <li className="muted">No drones yet.</li>}
            </ul>
          </section>

          {selectedDrone && (
            <section className="card">
              <h2>{selectedDrone.callsign}</h2>
              <dl className="kv">
                <dt>Drone ID</dt>
                <dd>{selectedDrone.id}</dd>
                <dt>Base</dt>
                <dd>{selectedDrone.homeBase}</dd>
                <dt>Status</dt>
                <dd>{selectedDrone.status}</dd>
                <dt>Battery</dt>
                <dd>{Math.round(selectedDrone.batteryPct)}%</dd>
                <dt>Parked at</dt>
                <dd>
                  {selectedDrone.lat.toFixed(4)}, {selectedDrone.lng.toFixed(4)}
                </dd>
              </dl>
            </section>
          )}

          <section className="card">
            <h2>How this works</h2>
            <ol className="explain">
              <li>
                <code>GET /api/drones</code> reads the registry (Supabase, or demo data) and drops
                a marker on the globe.
              </li>
              <li>
                <code>POST /api/missions</code> starts a <b>Temporal workflow</b>, not a thread.
                Temporal writes the mission into its durable history and returns immediately.
              </li>
              <li>
                <code>GET /api/missions/&#123;id&#125;</code> hits the workflow's{' '}
                <code>@QueryMethod</code> — live state, no database.
              </li>
              <li>Watch your runs under <a href="http://localhost:8088" target="_blank" rel="noreferrer">localhost:8088</a>.</li>
            </ol>
          </section>
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
