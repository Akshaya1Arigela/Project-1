import { useEffect, useRef, useState } from 'react';

/**
 * Live fleet telemetry over a raw WebSocket.
 *
 * <h2>Why the hook owns the socket rather than a component</h2>
 * <p>Because React's render cycle and a 1 Hz network stream have nothing to do with each other.
 * The socket writes into state directly, outside any render, and React simply re-renders with the
 * newest frame when it gets around to it. At 50 drones that is the difference between a smooth
 * globe and a stuttering one.</p>
 *
 * <h2>Trails</h2>
 * <p>Flight paths are accumulated here rather than in the map component. They are presentation
 * state, they are bounded (last {@link MAX_TRAIL_POINTS} samples per drone), and keeping them out
 * of React state avoids re-rendering the whole tree on every frame just to move a polyline.</p>
 */

const MAX_TRAIL_POINTS = 140;

function wsUrl() {
  const explicit = import.meta.env.VITE_WS_URL;
  if (explicit) return explicit;
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}/ws/telemetry`;
}

export function useFleetTelemetry() {
  const [state, setState] = useState({
    drones: [],
    homeBase: null,
    storm: false,
    connected: false,
    serverTime: null,
  });

  /** droneId -> [{lat,lng}] — a ref, because it changes every second and must not re-render. */
  const trailsRef = useRef(new Map());

  useEffect(() => {
    let socket = null;
    let disposed = false;
    let attempt = 0;

    function connect() {
      if (disposed) return;
      socket = new WebSocket(wsUrl());

      socket.onopen = () => {
        attempt = 0;
        setState((prev) => ({ ...prev, connected: true }));
      };

      socket.onmessage = (event) => {
        let frame;
        try {
          frame = JSON.parse(event.data);
        } catch {
          return; // a malformed frame should never break the dashboard
        }
        if (frame.type !== 'fleet.telemetry') return;

        accumulateTrails(trailsRef.current, frame.drones);
        setState({
          drones: frame.drones ?? [],
          homeBase: frame.homeBase ?? null,
          storm: Boolean(frame.storm),
          connected: true,
          serverTime: frame.serverTime ?? null,
        });
      };

      socket.onerror = () => socket.close();

      socket.onclose = () => {
        if (disposed) return;
        setState((prev) => ({ ...prev, connected: false }));
        // Exponential backoff capped at 10s, so a backend restart heals on its own.
        attempt += 1;
        setTimeout(connect, Math.min(1000 * 2 ** attempt, 10_000));
      };
    }

    connect();

    return () => {
      disposed = true;
      socket?.close();
    };
  }, []);

  return { ...state, trails: trailsRef.current };
}

/**
 * Append the latest position of every drone that is currently on a mission.
 *
 * <p>Drones that are not flying do not get a trail, and a drone that finishes its mission keeps
 * its last trail until it flies again — which reads better than a trail that vanishes mid-sentence.</p>
 */
function accumulateTrails(trails, drones) {
  for (const drone of drones) {
    if (!drone.missionId) continue;
    const trail = trails.get(drone.id) ?? [];
    const last = trail[trail.length - 1];
    if (last && Math.abs(last.lat - drone.lat) < 1e-7 && Math.abs(last.lng - drone.lng) < 1e-7) {
      continue; // no movement since the last frame; do not stack duplicate points
    }
    trail.push({ lat: drone.lat, lng: drone.lng, alt: drone.altitudeM });
    if (trail.length > MAX_TRAIL_POINTS) trail.splice(0, trail.length - MAX_TRAIL_POINTS);
    trails.set(drone.id, trail);
  }
}

export { MAX_TRAIL_POINTS };
