import { useEffect, useState } from 'react';
import { api } from '../lib/api.js';

const POLL_MS = 1500;

const PHASE_TONE = {
  QUEUED: 'muted',
  TAKEOFF: 'ok',
  NAVIGATING: 'ok',
  DROPPING: 'ok',
  RETURNING_TO_BASE: 'ok',
  OFFLINE_SUSPENDED: 'bad',
  COMPENSATING: 'warn',
  COMPENSATED: 'warn',
  DELIVERED: 'ok',
  FAILED: 'bad',
  ABORTED: 'bad',
};

/**
 * Live Temporal workflow state for every running mission.
 *
 * <p>The list polls <code>GET /api/missions</code>, which calls each workflow's
 * <code>@QueryMethod</code>. There is no job table behind this — it is the workflow object's own
 * private fields, read directly. That is why a mission that has been suspended for two hours
 * still reports <code>OFFLINE_SUSPENDED</code> with its original step number.</p>
 */
export default function MissionPanel({ selectedDroneId, onSelectMission, version }) {
  const [missions, setMissions] = useState([]);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(null);

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      try {
        const list = await api.missions();
        if (!cancelled) {
          setMissions(list);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) setError(e.message);
      }
    }

    poll();
    const timer = setInterval(poll, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [version]);

  async function act(missionId, action) {
    setBusy(`${action}:${missionId}`);
    try {
      if (action === 'abort') await api.abortMission(missionId);
      if (action === 'reconnect') await api.reconnectMission(missionId);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="card">
      <h2>Mission workflows</h2>
      <p className="muted">
        Live <code>@QueryMethod</code> state from Temporal. {missions.length} open.
      </p>

      {error && <p className="inline-error">{error}</p>}

      <ul className="mission-list">
        {missions.map((m) => (
          <li key={m.missionId} className={m.missionId === selectedDroneId ? 'selected' : ''}>
            <div className="mission-head">
              <button className="linkish" onClick={() => onSelectMission?.(m.missionId)}>
                <b>{m.missionId}</b>
              </button>
              <span className={`phase tone-${PHASE_TONE[m.phase] ?? 'muted'}`}>{m.phase}</span>
            </div>

            <div className="mission-meta">
              <span>{m.droneId}</span>
              <span>step {m.step}</span>
              <span>{Math.round(m.batteryPct ?? 0)}%</span>
            </div>

            <p className="mission-detail">{m.detail}</p>

            {m.phase === 'OFFLINE_SUSPENDED' && (
              <div className="mission-actions">
                <button
                  disabled={busy === `reconnect:${m.missionId}`}
                  onClick={() => act(m.missionId, 'reconnect')}
                >
                  {busy === `reconnect:${m.missionId}` ? 'Signalling…' : 'Wake up'}
                </button>
              </div>
            )}

            {!['DELIVERED', 'COMPENSATED', 'FAILED', 'ABORTED'].includes(m.phase) && (
              <div className="mission-actions">
                <button
                  className="secondary"
                  disabled={busy === `abort:${m.missionId}`}
                  onClick={() => act(m.missionId, 'abort')}
                >
                  Abort
                </button>
              </div>
            )}
          </li>
        ))}

        {missions.length === 0 && (
          <li className="muted empty">
            No open missions. Dispatch the fleet to start some Temporal workflows.
          </li>
        )}
      </ul>
    </section>
  );
}
