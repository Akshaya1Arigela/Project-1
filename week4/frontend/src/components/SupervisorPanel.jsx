import { useCallback, useEffect, useState } from 'react';
import { api } from '../lib/api.js';

/**
 * The fleet supervisor and the review-day drills.
 *
 * <p>Starting a supervisor starts a long-running Temporal workflow; stopping one sends a signal.
 * The tiles below are a query against that workflow, polled every three seconds — there is no
 * scheduler thread anywhere in the backend.</p>
 *
 * <p>The two knobs that make a live demo work: <b>grace</b> (how long a silent drone is tolerated
 * before it counts as an incident — turn it down to 5 to make the storm drill instant) and
 * <b>auto-intervene</b> (watch vs act).</p>
 */
export default function SupervisorPanel({ onActivity }) {
  const [supervisors, setSupervisors] = useState([]);
  const [incidents, setIncidents] = useState([]);
  const [policy, setPolicy] = useState({
    scanIntervalSeconds: 10,
    offlineGraceSeconds: 20,
    autoIntervene: true,
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    try {
      const [reports, feed] = await Promise.all([api.supervisors(), api.incidents(20)]);
      setSupervisors(reports);
      setIncidents(feed);
      setError(null);
    } catch (e) {
      setError(e.message);
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
      const report = await api.startSupervisor({
        scanIntervalSeconds: Number(policy.scanIntervalSeconds),
        offlineGraceSeconds: Number(policy.offlineGraceSeconds),
        autoIntervene: policy.autoIntervene,
      });
      setMessage(`Supervisor ${report.supervisorId} watching the fleet`);
      await refresh();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  async function stop(id) {
    try {
      await api.stopSupervisor(id);
      await refresh();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    }
  }

  async function toggle(id, enabled) {
    try {
      await api.setAutoIntervene(id, enabled);
      await refresh();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    }
  }

  async function loadTest(count) {
    setBusy(true);
    setMessage(null);
    try {
      const result = await api.loadTest({ count, payloadKg: 1.2, spreadKm: 7 });
      setMessage(
        `${result.dispatched} missions dispatched in ${result.elapsedMs} ms${result.failed ? ` (${result.failed} failed)` : ''}`,
      );
      onActivity?.();
      await refresh();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    } finally {
      setBusy(false);
    }
  }

  const active = supervisors.filter((s) => s.status !== 'STOPPED');

  return (
    <section className="card">
      <h2>Fleet supervisor</h2>

      {error && <p className="inline-error">Supervisor unavailable: {error}</p>}

      <div className="supervisor-form">
        <label>
          Scan (s)
          <input
            type="number"
            min="2"
            max="600"
            value={policy.scanIntervalSeconds}
            onChange={(e) => setPolicy({ ...policy, scanIntervalSeconds: e.target.value })}
          />
        </label>
        <label>
          Grace (s)
          <input
            type="number"
            min="1"
            max="3600"
            value={policy.offlineGraceSeconds}
            onChange={(e) => setPolicy({ ...policy, offlineGraceSeconds: e.target.value })}
          />
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={policy.autoIntervene}
            onChange={(e) => setPolicy({ ...policy, autoIntervene: e.target.checked })}
          />
          Auto home
        </label>
      </div>

      <button disabled={busy} onClick={start}>
        {busy ? 'Working…' : '🛰 Start supervisor'}
      </button>

      {message && <p className="control-message">{message}</p>}

      {active.length === 0 ? (
        <p className="muted">No supervisor running — the fleet is unmonitored.</p>
      ) : (
        <ul className="mission-list">
          {active.map((report) => (
            <li key={report.supervisorId}>
              <div className="mission-head">
                <strong>{report.supervisorId}</strong>
                <span className="phase">{report.status}</span>
              </div>

              <div className="supervisor-tiles">
                <div>
                  <span>Airframes</span>
                  <b>{report.dronesWatched}</b>
                </div>
                <div>
                  <span>Silent</span>
                  <b className={report.offline ? 'tone-bad' : ''}>{report.offline}</b>
                </div>
                <div>
                  <span>Flying</span>
                  <b>{report.onMission}</b>
                </div>
                <div>
                  <span>Incidents</span>
                  <b className={report.incidents ? 'tone-warn' : ''}>{report.incidents}</b>
                </div>
                <div>
                  <span>Sent home</span>
                  <b>{report.interventions}</b>
                </div>
                <div>
                  <span>Run</span>
                  <b>{report.run}</b>
                </div>
              </div>

              <p className="mission-detail">{report.detail}</p>

              <div className="mission-actions">
                <button
                  className="secondary"
                  onClick={() => toggle(report.supervisorId, !report.autoIntervene)}
                >
                  {report.autoIntervene ? '👁 Observe only' : '⚡ Allow intervention'}
                </button>
                <button className="danger" onClick={() => stop(report.supervisorId)}>
                  ■ Stop
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <h3 className="subhead">Incidents</h3>
      {incidents.length === 0 ? (
        <p className="muted">Nothing raised yet.</p>
      ) : (
        <ul className="incident-feed">
          {incidents.slice(0, 6).map((incident) => (
            <li key={incident.id}>
              <span className={`phase ${incident.severity === 'HIGH' ? 'tone-bad' : 'tone-warn'}`}>
                {incident.kind}
              </span>
              <span className="incident-who">{incident.droneId ?? 'fleet'}</span>
              <span className="incident-action">
                {incident.intervened ? '→ sent home' : '→ logged'}
              </span>
              <p>{incident.detail}</p>
            </li>
          ))}
        </ul>
      )}

      <h3 className="subhead">Load test</h3>
      <div className="button-grid">
        <button disabled={busy} onClick={() => loadTest(25)}>
          🚀 Dispatch 25 drones
        </button>
        <button disabled={busy} onClick={() => loadTest(50)}>
          🚀 Dispatch 50 drones
        </button>
      </div>
      <p className="hint">
        Every one of those is a real <code>DroneMissionWorkflow</code>. This is the review's visual
        check: 50+ markers moving while the globe stays smooth.
      </p>
    </section>
  );
}
