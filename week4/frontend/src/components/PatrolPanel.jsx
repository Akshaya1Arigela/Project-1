import { useCallback, useEffect, useState } from 'react';
import { api } from '../lib/api.js';

/**
 * Recurring patrols.
 *
 * <p>Start one and watch the countdown: the backend is not holding a timer thread, the workflow is
 * parked on a durable timer inside Temporal. Stop the backend mid-countdown, restart it, and the
 * patrol re-attaches and keeps flying — which is the whole argument for "schedule inside the
 * workflow, not in cron".</p>
 */
export default function PatrolPanel({ drones = [], onActivity }) {
  const [patrols, setPatrols] = useState([]);
  const [droneId, setDroneId] = useState('DRN-03');
  const [interval, setIntervalSeconds] = useState(30);
  const [cycles, setCycles] = useState(3);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  const refresh = useCallback(async () => {
    try {
      setPatrols(await api.patrols());
    } catch {
      /* health card reports the dead backend */
    }
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 3000);
    return () => clearInterval(timer);
  }, [refresh]);

  async function start() {
    setBusy(true);
    setMessage(null);
    try {
      const status = await api.startPatrol({
        droneId,
        intervalSeconds: Number(interval),
        cycles: Number(cycles),
      });
      setMessage(`Patrol ${status.patrolId} started — ${cycles} lap(s) every ${interval}s`);
      onActivity?.();
      await refresh();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function stop(patrolId) {
    try {
      await api.stopPatrol(patrolId);
      await refresh();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    }
  }

  return (
    <section className="card">
      <h2>Patrols</h2>

      <div className="patrol-form">
        <label>
          Drone
          <select value={droneId} onChange={(e) => setDroneId(e.target.value)}>
            {(drones.length ? drones.map((d) => d.id) : ['DRN-03']).map((id) => (
              <option key={id} value={id}>
                {id}
              </option>
            ))}
          </select>
        </label>
        <label>
          Every (s)
          <input
            type="number"
            min="10"
            max="600"
            value={interval}
            onChange={(e) => setIntervalSeconds(e.target.value)}
          />
        </label>
        <label>
          Laps
          <input
            type="number"
            min="1"
            max="20"
            value={cycles}
            onChange={(e) => setCycles(e.target.value)}
          />
        </label>
      </div>

      <button disabled={busy} onClick={start}>
        {busy ? 'Starting…' : '🔁 Start patrol'}
      </button>

      {message && <p className="control-message">{message}</p>}

      {patrols.length === 0 ? (
        <p className="muted">No patrol running.</p>
      ) : (
        <ul className="mission-list">
          {patrols.map((patrol) => (
            <li key={patrol.patrolId}>
              <div className="mission-head">
                <strong>{patrol.droneId}</strong>
                <span className="phase">{patrol.phaseLabel}</span>
              </div>
              <p className="mission-meta">
                Lap {patrol.cyclesCompleted}/{patrol.cyclesRequested}
                {patrol.nextCycleInSeconds > 0
                  ? ` · next in ${patrol.nextCycleInSeconds}s`
                  : ''}
              </p>
              <p className="mission-detail">{patrol.detail}</p>
              <div className="mission-actions">
                <button className="danger" onClick={() => stop(patrol.patrolId)}>
                  ■ Stop patrol
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
