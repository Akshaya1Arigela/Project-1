import { useEffect, useState } from 'react';
import { api } from '../lib/api.js';

/**
 * Fleet analytics, straight from the backend's mission ledger.
 *
 * <p>Polled every five seconds rather than streamed: these are totals, not positions, and a
 * number that changes once a mission finishes does not need a 1 Hz socket.</p>
 */
export default function AnalyticsPanel({ version = 0 }) {
  const [summary, setSummary] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      try {
        const result = await api.analytics();
        if (!cancelled) {
          setSummary(result);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) setError(e.message);
      }
    }

    poll();
    const timer = setInterval(poll, 5000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [version]);

  const peak = Math.max(1, ...(summary?.throughput ?? []).map((slot) => slot.delivered));

  return (
    <section className="card">
      <h2>Fleet analytics</h2>

      {error && <p className="inline-error">Analytics unavailable: {error}</p>}

      {!summary && !error && <p className="muted">Loading…</p>}

      {summary && (
        <>
          <dl className="kv">
            <dt>Missions recorded</dt>
            <dd>{summary.missionsRecorded}</dd>
            <dt>In flight</dt>
            <dd>{summary.missionsInFlight}</dd>
            <dt>Delivered</dt>
            <dd className="tone-ok">{summary.delivered}</dd>
            <dt>Compensated</dt>
            <dd className={summary.compensated ? 'tone-warn' : ''}>{summary.compensated}</dd>
            <dt>Failed / aborted</dt>
            <dd className={summary.failed + summary.aborted ? 'tone-bad' : ''}>
              {summary.failed} / {summary.aborted}
            </dd>
            <dt>Success rate</dt>
            <dd>{Math.round(summary.successRate * 100)}%</dd>
            <dt>Average flight</dt>
            <dd>{summary.averageFlightSeconds.toFixed(1)} s</dd>
            <dt>Suspensions</dt>
            <dd>{summary.suspensionEvents}</dd>
          </dl>

          <div className="analytics-bars" title="Deliveries per 5-minute bucket">
            {summary.throughput.map((slot) => (
              <div className="analytics-bar-col" key={slot.label}>
                <div
                  className={`analytics-bar ${slot.compensated ? 'warn' : ''}`}
                  style={{ height: `${Math.max(2, (slot.delivered / peak) * 100)}%` }}
                  title={`${slot.label} — ${slot.delivered} delivered, ${slot.compensated} compensated`}
                />
                <span className="analytics-bar-label">{slot.label}</span>
              </div>
            ))}
          </div>

          {summary.drones.length > 0 && (
            <table className="history-table compact">
              <thead>
                <tr>
                  <th>Drone</th>
                  <th>Missions</th>
                  <th>Delivered</th>
                  <th>Comp.</th>
                  <th>Last phase</th>
                </tr>
              </thead>
              <tbody>
                {summary.drones.slice(0, 6).map((drone) => (
                  <tr key={drone.droneId}>
                    <td>{drone.droneId}</td>
                    <td>{drone.missions}</td>
                    <td>{drone.delivered}</td>
                    <td>{drone.compensated}</td>
                    <td>{drone.lastPhase}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <p className="hint">
            Computed in the backend from every phase transition the activities reported — no
            database required. Supabase keeps the durable audit trail; this is the live view.
          </p>
        </>
      )}
    </section>
  );
}
