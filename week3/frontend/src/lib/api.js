/**
 * Thin fetch wrapper for the Spring Boot API.
 *
 * In development `VITE_API_BASE` is empty, so every call is same-origin and rides the Vite
 * proxy defined in vite.config.js. Set it to http://localhost:8080 to point elsewhere.
 */
const API_BASE = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '');

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.message) message = body.message;
    } catch {
      /* response was not JSON; keep the status text */
    }
    throw new Error(message);
  }

  return response.json();
}

export const api = {
  health: () => request('/api/health'),
  drones: () => request('/api/drones'),
  fleetState: () => request('/api/fleet/state'),
  telemetry: () => request('/api/telemetry'),

  missions: () => request('/api/missions'),
  mission: (id) => request(`/api/missions/${id}`),
  startMission: (payload) =>
    request('/api/missions', { method: 'POST', body: JSON.stringify(payload) }),
  abortMission: (id) => request(`/api/missions/${id}/abort`, { method: 'POST' }),
  reconnectMission: (id) => request(`/api/missions/${id}/reconnect`, { method: 'POST' }),

  dispatchFleet: (missions) =>
    request('/api/fleet/dispatch', { method: 'POST', body: JSON.stringify({ missions }) }),

  /** Supabase audit trail, proxied through the backend so the service-role key stays server-side. */
  history: () => request('/api/missions/history'),

  // ---------------------------------------------------------------- Week 3
  /** Multi-stop routes: one takeoff, N stops, one return. */
  routes: () => request('/api/routes'),
  route: (id) => request(`/api/routes/${id}`),
  startRoute: (payload) =>
    request('/api/routes', { method: 'POST', body: JSON.stringify(payload) }),
  abortRoute: (id) => request(`/api/routes/${id}/abort`, { method: 'POST' }),
  reconnectRoute: (id) => request(`/api/routes/${id}/reconnect`, { method: 'POST' }),

  /** Recurring patrols: a durable timer between laps, not a cron job. */
  patrols: () => request('/api/patrols'),
  patrol: (id) => request(`/api/patrols/${id}`),
  startPatrol: (payload) =>
    request('/api/patrols', { method: 'POST', body: JSON.stringify(payload) }),
  stopPatrol: (id) => request(`/api/patrols/${id}`, { method: 'DELETE' }),

  /** Fleet analytics from the in-memory ledger. */
  analytics: () => request('/api/analytics/summary'),
  timeline: (missionId) => request(`/api/analytics/timeline/${missionId}`),

  storm: (active) => request('/api/simulate/storm', { method: 'POST', body: JSON.stringify({ active }) }),
  reconnectAll: () => request('/api/simulate/reconnect', { method: 'POST', body: '{}' }),
  offline: (droneId, offline) =>
    request('/api/simulate/offline', { method: 'POST', body: JSON.stringify({ droneId, offline }) }),
  simulateStatus: () => request('/api/simulate/status'),
};
