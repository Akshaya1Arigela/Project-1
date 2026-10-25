import { useState } from 'react';
import { api } from '../lib/api.js';

/**
 * Operator controls — dispatching missions and injecting faults.
 *
 * <p>These buttons exist so the resiliency demo is reproducible on demand rather than dependent
 * on real weather. "Simulate Storm" flips a flag inside the backend; the workflow does not know
 * it is a simulation, it just sees its activity throw.</p>
 */
export default function ControlPanel({ storm, onActivity, homeBase }) {
  const [busy, setBusy] = useState(null);
  const [message, setMessage] = useState(null);

  async function run(label, fn) {
    setBusy(label);
    setMessage(null);
    try {
      const result = await fn();
      setMessage(result?.message ?? result?.detail ?? 'Done');
      onActivity?.();
    } catch (e) {
      setMessage(`✖ ${e.message}`);
    } finally {
      setBusy(null);
    }
  }

  function dispatchFleet() {
    const originLat = homeBase?.lat ?? 17.385;
    const originLng = homeBase?.lng ?? 78.4867;
    // Five destinations spread around the hub, so the markers fan out visibly.
    const targets = [
      ['Secunderabad Depot', 17.4375, 78.4483],
      ['HITEC City Hub', 17.4435, 78.3772],
      ['Begumpet Aerodrome', 17.4531, 78.4676],
      ['Uppal Cross-dock', 17.4126, 78.5522],
      ['Gachibowli Fulfilment', 17.4401, 78.3489],
    ];
    const missions = targets.slice(0, 5).map(([destinationLabel, destLat, destLng], i) => ({
      droneId: `DRN-0${i + 1}`,
      destinationLabel,
      destLat,
      destLng,
      payloadKg: 1.0 + i * 0.4,
    }));

    return run('dispatch', () => api.dispatchFleet(missions));
  }

  return (
    <section className="card">
      <h2>Mission control</h2>

      <div className="button-grid">
        <button disabled={busy === 'dispatch'} onClick={dispatchFleet}>
          {busy === 'dispatch' ? 'Dispatching…' : '🚀 Dispatch fleet (5)'}
        </button>

        <button
          className={storm ? 'secondary' : 'danger'}
          disabled={busy === 'storm'}
          onClick={() =>
            run('storm', () => api.storm(!storm))
          }
        >
          {busy === 'storm' ? '…' : storm ? '🌩 Clear storm' : '🌩 Simulate storm'}
        </button>

        <button
          className="secondary"
          disabled={busy === 'reconnect'}
          onClick={() => run('reconnect', () => api.reconnectAll())}
        >
          {busy === 'reconnect' ? '…' : '📡 Reconnect everything'}
        </button>

        <button
          className="secondary"
          disabled={busy === 'refresh'}
          onClick={() => run('refresh', async () => ({ message: 'Refreshed' }))}
        >
          ↻ Refresh
        </button>
      </div>

      {message && <p className="control-message">{message}</p>}

      <ol className="explain">
        <li>
          <b>Dispatch fleet</b> starts one <code>FleetMissionWorkflow</code> that fans out to five
          child missions and waits on <code>Promise.allOf</code>.
        </li>
        <li>
          <b>Simulate storm</b> → drones inside the corridor lose their link → their activity throws
          → the workflow parks on a durable <code>Workflow.await</code>.
        </li>
        <li>
          <b>Reconnect</b> signals every suspended workflow. They resume at the exact waypoint they
          stalled on.
        </li>
        <li>
          Kill the backend at any point (<code>Ctrl+C</code>) and restart it — the missions come
          back exactly where they were.
        </li>
      </ol>
    </section>
  );
}
