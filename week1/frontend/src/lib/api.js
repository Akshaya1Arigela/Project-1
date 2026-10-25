/**
 * Thin fetch wrapper for the Spring Boot API.
 *
 * In development `VITE_API_BASE` is empty, so every call is same-origin and rides the Vite
 * proxy defined in vite.config.js. Set it to http://localhost:8080 if you serve the frontend
 * from somewhere else.
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
  drones: () => request('/api/drones'),
  fleetState: () => request('/api/fleet/state'),
  health: () => request('/api/health'),
  missions: () => request('/api/missions'),
  mission: (id) => request(`/api/missions/${id}`),
  startMission: (payload) =>
    request('/api/missions', { method: 'POST', body: JSON.stringify(payload) }),
  abortMission: (id) => request(`/api/missions/${id}/abort`, { method: 'POST' }),
};
