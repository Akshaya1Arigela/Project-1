import { useCallback, useEffect, useState } from 'react';
import { api } from '../lib/api.js';

const POLL_MS = 10_000;

/**
 * The Supabase audit trail.
 *
 * <p>This is the one panel in the app whose data does <b>not</b> come from Temporal. Temporal
 * knows a mission is <em>running</em>; Supabase knows that it went OFFLINE_SUSPENDED at 14:02
 * and came back at 16:03. That two-hour gap in this table is your resiliency evidence.</p>
 *
 * <p>Reads go through the Java backend (<code>GET /api/missions/history</code>) rather than
 * straight from the browser, so the service-role key never leaves the server. If you later want
 * direct browser access, use the <b>anon</b> key plus the RLS SELECT policies in schema.sql.</p>
 */
export default function HistoryPanel() {
  const [rows, setRows] = useState([]);
  const [status, setStatus] = useState('loading');

  const refresh = useCallback(async () => {
    try {
      const history = await api.history();
      setRows(history);
      setStatus('ok');
    } catch (e) {
      setStatus(e.message);
    }
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, POLL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  return (
    <section className="card">
      <h2>Mission history</h2>
      <p className="muted">
        Supabase <code>missions</code> table — the durable audit trail Temporal does not keep.
      </p>

      {status !== 'ok' && (
        <p className="muted">
          {status === 'loading' ? 'Loading…' : `History unavailable: ${status}`}
          <br />
          <span className="hint">
            Run <code>week2/supabase/schema.sql</code> in the Supabase SQL editor, then set{' '}
            <code>SUPABASE_URL</code> and <code>SUPABASE_SERVICE_ROLE_KEY</code> in{' '}
            <code>backend/.env</code>.
          </span>
        </p>
      )}

      {status === 'ok' && rows.length === 0 && (
        <p className="muted">No missions recorded yet.</p>
      )}

      {rows.length > 0 && (
        <table className="history-table">
          <thead>
            <tr>
              <th>Mission</th>
              <th>Drone</th>
              <th>Phase</th>
              <th>Battery</th>
            </tr>
          </thead>
          <tbody>
            {rows.slice(0, 25).map((row) => (
              <tr key={`${row.mission_id}-${row.updated_at}`}>
                <td>{row.mission_id}</td>
                <td>{row.drone_id}</td>
                <td>
                  <span className={`phase ${phaseTone(row.phase)}`}>{row.phase}</span>
                </td>
                <td>{row.battery_pct == null ? '—' : `${Math.round(row.battery_pct)}%`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function phaseTone(phase) {
  if (['DELIVERED'].includes(phase)) return 'tone-ok';
  if (['OFFLINE_SUSPENDED', 'FAILED'].includes(phase)) return 'tone-bad';
  if (['COMPENSATED', 'COMPENSATING'].includes(phase)) return 'tone-warn';
  return '';
}
