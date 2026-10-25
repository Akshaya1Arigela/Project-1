import { useEffect, useRef } from 'react';
import { createViewer, syncDroneMarkers, addHomeBase, flyToHome } from '../lib/cesiumMap.js';

/**
 * The globe.
 *
 * The whole Cesium viewer lives inside this component's ref and is created exactly once in a
 * `useEffect`. React never re-renders Cesium's DOM subtree — it just hands the latest drone list
 * down through props, and `syncDroneMarkers` diffs it into existing entities.
 *
 * That split is what keeps 50 markers at 60 fps: React is out of the render loop entirely.
 */
export default function FleetMap({ drones, homeBase, onDroneSelect }) {
  const containerRef = useRef(null);
  const viewerRef = useRef(null);
  const onSelectRef = useRef(onDroneSelect);

  // Keep the latest callback without re-creating the viewer.
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

  // Push the latest drone positions into Cesium whenever they change.
  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.isDestroyed()) return;
    syncDroneMarkers(viewer, drones, (id) => onSelectRef.current?.(id));
  }, [drones]);

  // Draw the home base once it resolves.
  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.isDestroyed() || !homeBase) return;
    addHomeBase(viewer, homeBase);
  }, [homeBase]);

  return (
    <div className="map-shell">
      <div ref={containerRef} className="cesium-container" />
      {!homeBase && <div className="map-loading">Acquiring fleet telemetry…</div>}
    </div>
  );
}

export { flyToHome };
