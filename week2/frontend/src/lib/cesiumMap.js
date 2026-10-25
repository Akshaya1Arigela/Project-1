import * as Cesium from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';

/**
 * One place that knows how to build the Cesium Viewer.
 *
 * ## No Cesium Ion token
 * The default `new Viewer(el)` pulls imagery from Cesium Ion, which needs an API key and a
 * credit card. We pass an explicit `baseLayer` built from OpenStreetMap raster tiles instead —
 * free and accountless. (Cesium removed the old `imageryProvider` option in 1.107; `baseLayer`
 * is the current API.)
 *
 * ## Why this file never imports React
 * The React layer hands it data; it hands back nothing but side effects on a viewer instance.
 * That boundary is what keeps 50 animated markers off React's render path.
 */

const DEFAULT_CENTER = (import.meta.env.VITE_MAP_CENTER || '17.3850,78.4867')
  .split(',')
  .map((n) => Number(n.trim()));

/** Marker colours, keyed first by workflow phase (when on a mission) then by registry status. */
export const PHASE_COLORS = {
  OFFLINE_SUSPENDED: Cesium.Color.fromCssColorString('#ef4444'),
  COMPENSATING: Cesium.Color.fromCssColorString('#f97316'),
  COMPENSATED: Cesium.Color.fromCssColorString('#f59e0b'),
  DROPPING: Cesium.Color.fromCssColorString('#a78bfa'),
  RETURNING_TO_BASE: Cesium.Color.fromCssColorString('#38bdf8'),
  NAVIGATING: Cesium.Color.fromCssColorString('#22c55e'),
  TAKEOFF: Cesium.Color.fromCssColorString('#4ade80'),
  FAILED: Cesium.Color.fromCssColorString('#b91c1c'),
};

export const STATUS_COLORS = {
  idle: Cesium.Color.fromCssColorString('#22c55e'),
  charging: Cesium.Color.fromCssColorString('#38bdf8'),
  maintenance: Cesium.Color.fromCssColorString('#f59e0b'),
  'on-mission': Cesium.Color.fromCssColorString('#a78bfa'),
  unknown: Cesium.Color.fromCssColorString('#94a3b8'),
};

export function createViewer(container) {
  const [lat, lng] = DEFAULT_CENTER;

  const viewer = new Cesium.Viewer(container, {
    baseLayer: new Cesium.ImageryLayer(
      new Cesium.OpenStreetMapImageryProvider({
        url: 'https://tile.openstreetmap.org/',
        maximumLevel: 19,
      }),
    ),
    terrainProvider: new Cesium.EllipsoidTerrainProvider(),

    baseLayerPicker: false,
    geocoder: false,
    homeButton: false,
    sceneModePicker: false,
    navigationHelpButton: false,
    animation: false,
    timeline: false,
    fullscreenButton: false,
    infoBox: false,
    selectionIndicator: false,
    shouldAnimate: true,
  });

  viewer.scene.globe.enableLighting = false;
  viewer.scene.globe.depthTestAgainstTerrain = false;
  viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString('#0b1220');
  viewer.scene.skyAtmosphere.show = true;
  viewer.scene.fog.enabled = true;
  viewer.scene.fog.density = 0.00012;

  flyToHome(viewer, lat, lng);
  return viewer;
}

export function flyToHome(viewer, lat, lng, altitudeM = 34_000) {
  viewer.camera.flyTo({
    destination: Cesium.Cartesian3.fromDegrees(lng, lat, altitudeM),
    duration: 2.5,
  });
}

/**
 * Draw or move the drone markers, diffed by id.
 *
 * <p>Called once per frame. Existing entities are mutated in place; new ones are created; stale
 * ones are removed. Rebuilding the whole collection every tick would make the globe stutter at
 * 50 markers.</p>
 */
export function syncDroneMarkers(viewer, drones, onSelect) {
  const entities = viewer.entities;
  const seen = new Set();

  for (const drone of drones) {
    if (drone.lat == null || drone.lng == null) continue;
    seen.add(drone.id);

    const position = Cesium.Cartesian3.fromDegrees(drone.lng, drone.lat, drone.altitudeM ?? 0);
    const color = markerColor(drone);
    const suspended = drone.phase === 'OFFLINE_SUSPENDED' || drone.linkUp === false;

    let entity = entities.getById(drone.id);
    if (!entity) {
      entity = entities.add({
        id: drone.id,
        position,
        point: {
          pixelSize: 13,
          color,
          outlineColor: Cesium.Color.WHITE.withAlpha(0.85),
          outlineWidth: 2,
          // Never let a marker disappear behind the globe — it reads as a bug.
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        },
        label: {
          text: drone.id,
          font: '600 12px "Segoe UI", system-ui, sans-serif',
          fillColor: Cesium.Color.WHITE,
          outlineColor: Cesium.Color.BLACK,
          outlineWidth: 3,
          style: Cesium.LabelStyle.FILL_AND_OUTLINE,
          verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
          pixelOffset: new Cesium.Cartesian2(0, -18),
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        },
      });
      if (onSelect) entity.onClick = () => onSelect(drone.id);
    } else {
      entity.position = position;
      entity.point.color = color;
      entity.point.pixelSize = suspended ? 20 : 13;
    }

    entity.description = new Cesium.ConstantProperty(tipFor(drone));
  }

  for (const existing of entities.values.slice()) {
    if (existing.id.startsWith('trail-')) continue; // flight paths are cleaned up in syncTrails
    if (!seen.has(existing.id) && existing.id !== 'home-base' && existing.id !== 'storm-zone') {
      entities.remove(existing);
    }
  }
}

/**
 * Draw the flight paths.
 *
 * <p>One polyline per drone that is currently on a mission, built from the accumulated trail
 * samples. Kept separate from the markers so a marker can move at 1 Hz without re-creating
 * geometry it is not part of.</p>
 */
export function syncTrails(viewer, trails, activeDroneIds) {
  const active = new Set(activeDroneIds);

  for (const [droneId, points] of trails) {
    if (!active.has(droneId) || points.length < 2) continue;
    const positions = points.map((p) => Cesium.Cartesian3.fromDegrees(p.lng, p.lat, (p.alt ?? 0) + 1));
    const id = `trail-${droneId}`;
    let polyline = viewer.entities.getById(id);
    if (!polyline) {
      polyline = viewer.entities.add({
        id,
        polyline: {
          positions,
          width: 2.5,
          material: Cesium.Color.fromCssColorString('#38bdf8').withAlpha(0.65),
          clampToGround: false,
        },
      });
    } else {
      polyline.polyline.positions = new Cesium.ConstantProperty(positions);
    }
  }

  // Drop trails for drones that are no longer flying.
  for (const id of Array.from(trailEntityIds(viewer))) {
    const droneId = id.replace(/^trail-/, '');
    if (!active.has(droneId)) viewer.entities.removeById(id);
  }
}

/** The storm corridor, drawn as a translucent red rectangle over the hub. */
export function syncStormZone(viewer, active, bounds) {
  const existing = viewer.entities.getById('storm-zone');
  if (!active) {
    if (existing) viewer.entities.remove(existing);
    return;
  }
  if (!bounds) return;

  const rectangle = {
    coordinates: Cesium.Rectangle.fromDegrees(
      bounds.west,
      bounds.south,
      bounds.east,
      bounds.north,
    ),
    material: Cesium.Color.fromCssColorString('#ef4444').withAlpha(0.18),
    outline: true,
    outlineColor: Cesium.Color.fromCssColorString('#ef4444').withAlpha(0.9),
    classificationType: Cesium.ClassificationType.BOTH,
  };

  if (existing) {
    existing.rectangle = rectangle;
  } else {
    viewer.entities.add({ id: 'storm-zone', rectangle });
  }
}

/** Home base, as a distinct diamond. */
export function addHomeBase(viewer, { label, lat, lng }) {
  const existing = viewer.entities.getById('home-base');
  if (existing) {
    existing.position = Cesium.Cartesian3.fromDegrees(lng, lat, 0);
    return existing;
  }
  return viewer.entities.add({
    id: 'home-base',
    position: Cesium.Cartesian3.fromDegrees(lng, lat, 0),
    point: {
      pixelSize: 18,
      color: Cesium.Color.fromCssColorString('#38bdf8'),
      outlineColor: Cesium.Color.fromCssColorString('#0b1220'),
      outlineWidth: 3,
      disableDepthTestDistance: Number.POSITIVE_INFINITY,
    },
    label: {
      text: `⌂ ${label}`,
      font: '700 13px "Segoe UI", system-ui, sans-serif',
      fillColor: Cesium.Color.fromCssColorString('#38bdf8'),
      outlineColor: Cesium.Color.BLACK,
      outlineWidth: 3,
      style: Cesium.LabelStyle.FILL_AND_OUTLINE,
      verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
      pixelOffset: new Cesium.Cartesian2(0, -22),
      disableDepthTestDistance: Number.POSITIVE_INFINITY,
    },
  });
}

// ------------------------------------------------------------------ helpers

function markerColor(drone) {
  if (drone.linkUp === false) return PHASE_COLORS.OFFLINE_SUSPENDED;
  if (drone.phase && PHASE_COLORS[drone.phase]) return PHASE_COLORS[drone.phase];
  return STATUS_COLORS[drone.status] ?? STATUS_COLORS.unknown;
}

function tipFor(drone) {
  const link = drone.linkUp === false
    ? '<span class="tip-bad">LINK LOST — workflow suspended</span><br/>'
    : '';
  return `<div class="cesium-drone-tip">
    <b>${escapeHtml(drone.callsign || drone.id)}</b><br/>
    ${link}
    Phase ${escapeHtml(drone.phase ?? '—')}<br/>
    Battery ${Math.round(drone.batteryPct ?? 0)}%<br/>
    Altitude ${Math.round(drone.altitudeM ?? 0)} m<br/>
    Mission ${drone.missionId ? escapeHtml(drone.missionId) : '—'}
  </div>`;
}

/**
 * Trail entity ids currently in the viewer. Trail cleanup lives in {@link syncTrails}; this is
 * the only place that knows their id convention, so marker cleanup never has to care about them.
 */
function trailEntityIds(viewer) {
  return viewer.entities.values
    .filter((e) => typeof e.id === 'string' && e.id.startsWith('trail-'))
    .map((e) => e.id);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}
