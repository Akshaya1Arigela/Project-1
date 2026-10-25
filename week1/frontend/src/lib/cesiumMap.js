import * as Cesium from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';

/**
 * One place that knows how to build the Cesium Viewer, so week 1 and week 2 look identical.
 *
 * ## No Cesium Ion token
 * The default `new Viewer(el)` pulls imagery from Cesium Ion, which needs an API key and a
 * credit card. We pass an explicit `baseLayer` built from OpenStreetMap raster tiles instead,
 * which is free and needs no account. (Cesium removed the old `imageryProvider` option in
 * 1.107 — `baseLayer` is the current API.)
 *
 * ## Determinism note
 * Everything Cesium here is plain imperative JS against a viewer instance. The React layer never
 * touches Cesium's internals; it hands data to `syncDroneMarkers` and lets Cesium do its thing.
 */

const DEFAULT_CENTER = (import.meta.env.VITE_MAP_CENTER || '17.3850,78.4867')
  .split(',')
  .map((n) => Number(n.trim()));

export const STATUS_COLORS = {
  idle: Cesium.Color.fromCssColorString('#22c55e'),
  charging: Cesium.Color.fromCssColorString('#38bdf8'),
  maintenance: Cesium.Color.fromCssColorString('#f59e0b'),
  'on-mission': Cesium.Color.fromCssColorString('#a78bfa'),
  OFFLINE_SUSPENDED: Cesium.Color.fromCssColorString('#ef4444'),
  unknown: Cesium.Color.fromCssColorString('#94a3b8'),
};

export function createViewer(container) {
  const [lat, lng] = DEFAULT_CENTER;

  const viewer = new Cesium.Viewer(container, {
    // Free OSM imagery — no Ion token, no account.
    baseLayer: new Cesium.ImageryLayer(
      new Cesium.OpenStreetMapImageryProvider({
        url: 'https://tile.openstreetmap.org/',
        maximumLevel: 19,
      }),
    ),
    terrainProvider: new Cesium.EllipsoidTerrainProvider(),

    // Strip the default chrome; we build our own controls in React.
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

  // Keep the globe readable and cheap: no atmospheric fog fading markers out, no terrain
  // depth-testing (we have no terrain provider, so markers would otherwise z-fight).
  viewer.scene.globe.enableLighting = false;
  viewer.scene.globe.depthTestAgainstTerrain = false;
  viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString('#0b1220');
  viewer.scene.skyAtmosphere.show = true;
  viewer.scene.fog.enabled = true;
  viewer.scene.fog.density = 0.00012;

  flyToHome(viewer, lat, lng);
  return viewer;
}

export function flyToHome(viewer, lat, lng, altitudeM = 42_000) {
  viewer.camera.flyTo({
    destination: Cesium.Cartesian3.fromDegrees(lng, lat, altitudeM),
    duration: 2.5,
  });
}

/**
 * Draw or move the drone markers.
 *
 * Diffed by id so calling this 60 times a second (week 2) is cheap: existing entities get their
 * position/battery updated, new ones are created, and stale ones are removed. Rebuilding the
 * whole collection every tick would make the globe stutter at 50 drones.
 *
 * @param {Cesium.Viewer} viewer
 * @param {Array<{id,callsign,lat,lng,altitudeM,batteryPct,status}>} drones
 * @param {(droneId: string) => void} [onSelect]
 */
export function syncDroneMarkers(viewer, drones, onSelect) {
  const entities = viewer.entities;
  const seen = new Set();

  for (const drone of drones) {
    if (drone.lat == null || drone.lng == null) continue;
    seen.add(drone.id);

    const position = Cesium.Cartesian3.fromDegrees(drone.lng, drone.lat, drone.altitudeM ?? 60);
    const color = STATUS_COLORS[drone.status] ?? STATUS_COLORS.unknown;
    let entity = entities.getById(drone.id);

    if (!entity) {
      entity = entities.add({
        id: drone.id,
        position,
        point: {
          pixelSize: 14,
          color,
          outlineColor: Cesium.Color.WHITE.withAlpha(0.85),
          outlineWidth: 2,
          disableDepthTestDistance: Number.POSITIVE_INFINITY, // never hide behind the globe
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
        properties: { status: drone.status },
      });
      if (onSelect) {
        entity.onClick = () => onSelect(drone.id);
      }
    } else {
      entity.position = position;
      entity.point.color = color;
      entity.label.text = drone.id;
    }

    // A compact status chip under the marker: "DRN-01 · 87%"
    entity.description = new Cesium.ConstantProperty(
      `<div class="cesium-drone-tip">
         <b>${escapeHtml(drone.callsign || drone.id)}</b><br/>
         Battery ${Math.round(drone.batteryPct ?? 0)}%<br/>
         Altitude ${Math.round(drone.altitudeM ?? 0)} m<br/>
         Status ${escapeHtml(drone.status || 'unknown')}
       </div>`,
    );
  }

  for (const existing of entities.values.slice()) {
    if (!seen.has(existing.id)) entities.remove(existing);
  }
}

/** Draw the home base once, as a distinct diamond marker. */
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

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}
