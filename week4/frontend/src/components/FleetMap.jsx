import { useEffect, useRef } from 'react';
import {
  createViewer,
  syncDroneMarkers,
  syncTrails,
  syncStormZone,
  addHomeBase,
  flyToHome,
} from '../lib/cesiumMap.js';

/**
 * The globe.
 *
 * The Cesium viewer lives inside this component's ref and is created exactly once in a
 * `useEffect`. React never re-renders Cesium's DOM subtree — it hands the latest data down
 * through props, and Cesium diffs it into existing entities.
 *
 * That split is what keeps 50 markers at 60 fps: React is not in the render loop at all.
 */
export default function FleetMap({ drones, trails, homeBase, storm, stormBounds, onDroneSelect }) {
  const containerRef = useRef(null);
  const viewerRef = useRef(null);
  const onSelectRef = useRef(onDroneSelect);

  useEffect(() => {
    onSelectRef.current = onDroneSelect;
  }, [onDroneSelect]);

  useEffect(() => {
    const viewer = createViewer(containerRef.current);
    viewerRef.current = viewer;
    return () => {
      viewerRef.current = null;
      if (!viewer.isDestroyed()) viewer.destroy();
    };
  }, []);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.isDestroyed()) return;
    syncDroneMarkers(viewer, drones, (id) => onSelectRef.current?.(id));
    syncTrails(
      viewer,
      trails,
      drones.filter((d) => d.missionId).map((d) => d.id),
    );
  }, [drones, trails]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.isDestroyed()) return;
    syncStormZone(viewer, storm, stormBounds);
  }, [storm, stormBounds]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.isDestroyed() || !homeBase) return;
    addHomeBase(viewer, homeBase);
  }, [homeBase]);

  return (
    <div className="map-shell">
      <div ref={containerRef} className="cesium-container" />
      {storm && <div className="storm-banner">⚠ STORM ACTIVE — drones in the corridor lose their link</div>}
      {!homeBase && <div className="map-loading">Acquiring fleet telemetry…</div>}
    </div>
  );
}

export { flyToHome };
