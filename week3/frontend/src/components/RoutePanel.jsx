import { useCallback, useEffect, useState } from 'react';
import { api } from '../lib/api.js';

/**
 * Multi-stop route dispatch.
 *
 * <p>The four stops are sent in a deliberately shuffled order. The workflow's {@code planRoute}
 * activity re-orders them, and the panel shows the order that was actually flown — the cheapest
 * way to prove the optimisation ran inside the workflow rather than in the browser.</p>
 */
const STOPS = [
  { label: 'Uppal Cross-dock', lat: 17.4126, lng: 78.5522 },
  { label: 'Gachibowli Fulfilment', lat: 17.4401, lng: 78.3489 },
  { label: 'Secunderabad Depot', lat: 17.4375, lng: 78.4483 },
  { label: 'HITEC City Hub', lat: 17.4435, lng: 78.3772 },
];

export default function RoutePanel({ drones = [], onActivity }) {
  const [routes, setRoutes] = useState([]);
  const [droneId, setDroneId] = useState('DRN-04');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  const refresh = useCallback(async () => {
    try {
      setRoutes(await api.routes());
    } catch {
      /* the health card already reports a dead backend */
    }
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 3000);
    return () => clearInterval(timer);
  }, [refresh]);

  async function dispatch() {
    setBusy(true);
    setMessage(null);
    try {
      const result = await api.startRoute({ droneId, stops: STOPS, payloadKg: 2.4 });
      setMessage(`Route ${result.missionId} started — ${result.stopsRequested} stops`);
      onActivity?.();
      await refresh();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function act(fn, id) {
    setMessage(null);
    try {
      await fn(id);
      await refresh();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    }
  }

  return (
    <section className="card">
      <h2>Multi-stop route</h2>

      <div className="button-grid">
        <select value={droneId} onChange={(e) => setDroneId(e.target.value)}>
          {(drones.length ? drones.map((d) => d.id) : ['DRN-04']).map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
        <button disabled={busy} onClick={dispatch}>
          {busy ? 'Dispatching…' : '🗺 Fly 4-stop route'}
        </button>
      </div>

      {message && <p className="control-message">{message}</p>}

      {routes.length === 0 ? (
        <p className="muted">No route airborne.</p>
      ) : (
        <ul className="mission-list">
          {routes.map((route) => (
            <li key={route.missionId}>
              <div className="mission-head">
                <strong>{route.droneId}</strong>
                <span className={`phase phase-${(route.phase || '').toLowerCase()}`}>
                  {route.phase}
                </span>
              </div>
              <p className="mission-meta">
                {route.stopsDelivered}/{route.stopsPlanned} stops ·{' '}
                {route.totalDistanceKm.toFixed(1)} km planned · battery{' '}
                {Math.round(route.batteryPct)}%
              </p>
              <p className="mission-detail">{route.detail}</p>
              {route.plannedRoute?.length > 0 && (
                <p className="mission-detail">
                  Flown order: {route.plannedRoute.map((s) => s.label).join(' → ')}
                </p>
              )}
              <div className="mission-actions">
                <button className="secondary" onClick={() => act(api.reconnectRoute, route.missionId)}>
                  📡 Reconnect
                </button>
                <button className="danger" onClick={() => act(api.abortRoute, route.missionId)}>
                  ✕ Abort
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <p className="hint">
        One <code>RouteMissionWorkflow</code> per flight: takeoff once, visit the stops in the order
        the <code>planRoute</code> activity returned, come home once. Low battery abandons the
        remaining stops and compensates.
      </p>
    </section>
  );
}
