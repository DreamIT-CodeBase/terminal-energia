'use client';

import React, {
  useEffect,
  useMemo,
  useRef
} from 'react';

import {
  Circle,
  MapContainer,
  Marker,
  TileLayer,
  Tooltip,
  useMap,
  useMapEvents
} from 'react-leaflet';

import L from 'leaflet';

import 'leaflet/dist/leaflet.css';

import { Station } from '../../types';

import {
  DEFAULT_CENTRE,
  DEFAULT_ZOOM,
  FOCUS_ZOOM,
  hasPosition,
  Point,
  SEARCH_ZOOM,
  stationLabel,
  STATION_COLOUR
} from './shared';


interface StationMapProps {
  stations: Station[];
  selectedId: string | null;
  // Set while a new station is being placed: the next click on the map is its
  // position, rather than a deselect.
  isPlacing: boolean;
  // Where the pending new station sits, once it has been placed.
  pendingPosition: [number, number] | null;
  // Only the selected station is draggable, so a stray drag cannot move a
  // forecourt nobody is looking at.
  isDraggable: boolean;
  // A coordinate pair typed into the search box: marked on the map and
  // flown to, so the search says where as well as what.
  searchPoint: Point | null;
  // The capture trigger's enter_radius_m from the device config. When set, a
  // ring of this radius is drawn around every plotted station so the geofence
  // that starts a capture is visible. Null while it is still loading or absent.
  triggerRadiusM: number | null;
  onSelect: (stationId: string | null) => void;
  onMove: (stationId: string, latitude: number, longitude: number) => void;
  onPlace: (latitude: number, longitude: number) => void;
}


// ============================================================
// MARKER
//
// A div icon rather than Leaflet's default image pin: it needs no
// image assets, still drags, and grows when selected.
// ============================================================

const markerIcon = (
  colour: string,
  isSelected: boolean
): L.DivIcon => {

  const size = isSelected ? 18 : 12;

  return L.divIcon({
    className: '',
    html: [
      '<span style="',
      'display:block;',
      `width:${size}px;`,
      `height:${size}px;`,
      'border-radius:9999px;',
      `background:${colour};`,
      `border:2px solid ${isSelected ? '#ffffff' : 'rgba(0,0,0,0.55)'};`,
      `box-shadow:0 0 0 ${isSelected ? 4 : 0}px rgba(255,255,255,0.25);`,
      '"></span>'
    ].join(''),
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2]
  });
};


const PENDING_ICON = L.divIcon({
  className: '',
  html: [
    '<span style="',
    'display:block;width:18px;height:18px;border-radius:9999px;',
    'background:#22c55e;border:2px solid #ffffff;',
    'box-shadow:0 0 0 4px rgba(34,197,94,0.35);',
    '"></span>'
  ].join(''),
  iconSize: [18, 18],
  iconAnchor: [9, 9]
});


/**
 * The searched coordinate itself. A ring rather than a filled dot, so it
 * reads as a place being looked at and never as another station.
 */
const SEARCH_ICON = L.divIcon({
  className: '',
  html: [
    '<span style="',
    'display:block;width:22px;height:22px;border-radius:9999px;',
    'background:rgba(37,99,235,0.15);border:2px solid #2563eb;',
    'box-shadow:0 0 0 3px rgba(37,99,235,0.25);',
    '"></span>'
  ].join(''),
  iconSize: [22, 22],
  iconAnchor: [11, 11]
});


// ============================================================
// MAP EVENTS
//
// A click on empty map either places the station being added or
// clears the selection. Both need the map instance, which only a
// child of MapContainer can reach.
// ============================================================

const MapClicks: React.FC<{
  isPlacing: boolean;
  onSelect: (stationId: string | null) => void;
  onPlace: (latitude: number, longitude: number) => void;
}> = ({
  isPlacing,
  onSelect,
  onPlace
}) => {

  useMapEvents({
    click: event => {

      if (isPlacing) {
        onPlace(event.latlng.lat, event.latlng.lng);
        return;
      }

      onSelect(null);
    }
  });

  return null;
};


/**
 * Pans to the selected station when the selection changes -- picking one out
 * of the rail, for instance. It fires on a change of id only, so dragging the
 * selected marker does not make the view chase it.
 */
const FocusSelected: React.FC<{
  station: Station | null;
}> = ({
  station
}) => {

  const map = useMap();

  const lastFocused = useRef<string | null>(null);

  useEffect(
    () => {

      if (!station || !hasPosition(station)) {
        lastFocused.current = null;
        return;
      }

      if (lastFocused.current === station.id) return;

      lastFocused.current = station.id;

      map.flyTo(
        [station.latitude as number, station.longitude as number],
        Math.max(map.getZoom(), FOCUS_ZOOM),
        { duration: 0.6 }
      );
    },
    [station, map]
  );

  return null;
};


/**
 * Flies to a searched coordinate when it changes. Stops short of the zoom a
 * selected station gets: the point of searching a position is to see what is
 * around it, which a street-level zoom would crop out.
 */
const FocusPoint: React.FC<{
  point: Point | null;
}> = ({
  point
}) => {

  const map = useMap();

  const lastFocused = useRef<string | null>(null);

  useEffect(
    () => {

      if (!point) {
        lastFocused.current = null;
        return;
      }

      const key = `${point.latitude},${point.longitude}`;

      if (lastFocused.current === key) return;

      lastFocused.current = key;

      map.flyTo(
        [point.latitude, point.longitude],
        Math.max(map.getZoom(), SEARCH_ZOOM),
        { duration: 0.6 }
      );
    },
    [point, map]
  );

  return null;
};


/** Leaflet measures the container once; a pane resize needs telling. */
const InvalidateOnResize: React.FC = () => {

  const map = useMap();

  useEffect(
    () => {

      const observer = new ResizeObserver(
        () => map.invalidateSize()
      );

      observer.observe(map.getContainer());

      return () => observer.disconnect();
    },
    [map]
  );

  return null;
};


/** The cursor says whether the next click drops a marker. */
const PlacingCursor: React.FC<{
  isPlacing: boolean;
}> = ({
  isPlacing
}) => {

  const map = useMap();

  useEffect(
    () => {

      map.getContainer().style.cursor = isPlacing ? 'crosshair' : '';
    },
    [isPlacing, map]
  );

  return null;
};


// ============================================================
// STATION MAP
// ============================================================

export const StationMap: React.FC<StationMapProps> = ({
  stations,
  selectedId,
  isPlacing,
  pendingPosition,
  isDraggable,
  searchPoint,
  triggerRadiusM,
  onSelect,
  onMove,
  onPlace
}) => {

  // A station with no coordinates cannot be drawn. It stays in the rail, where
  // it can be selected and given a position.
  const plotted = useMemo(
    () => stations.filter(hasPosition),
    [stations]
  );

  const selected = useMemo(
    () =>
      stations.find(station => station.id === selectedId) ?? null,
    [stations, selectedId]
  );

  return (

    <MapContainer
      center={DEFAULT_CENTRE}
      zoom={DEFAULT_ZOOM}
      className="w-full h-full bg-[#0a0a0c]"
      scrollWheelZoom
      // Hundreds of trigger-radius rings are cheap on canvas and costly as SVG.
      preferCanvas
    >

      <TileLayer
        attribution={
          '&copy; <a href="https://www.openstreetmap.org/copyright">' +
          'OpenStreetMap</a> contributors'
        }
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        maxZoom={19}
      />

      <MapClicks
        isPlacing={isPlacing}
        onSelect={onSelect}
        onPlace={onPlace}
      />

      <FocusSelected station={selected} />

      <FocusPoint point={searchPoint} />

      <InvalidateOnResize />

      <PlacingCursor isPlacing={isPlacing} />


      {/* CAPTURE TRIGGER RADIUS
          One ring per station at the config's enter_radius_m: the geofence a
          device has to cross for a capture to start. Non-interactive so a click
          still reaches the marker or the map underneath. */}

      {
        triggerRadiusM != null &&
        plotted.map(station => (
          <Circle
            key={`trigger-${station.id}`}
            center={[
              station.latitude as number,
              station.longitude as number
            ]}
            radius={triggerRadiusM}
            pathOptions={{
              color: '#38bdf8',
              weight: 1,
              opacity: 0.5,
              fillColor: '#38bdf8',
              fillOpacity: 0.08
            }}
            interactive={false}
          />
        ))
      }


      {
        plotted.map(station => {

          const isSelected = station.id === selectedId;

          return (

            <Marker
              key={station.id}
              position={[
                station.latitude as number,
                station.longitude as number
              ]}
              icon={markerIcon(STATION_COLOUR, isSelected)}
              draggable={isSelected && isDraggable}
              eventHandlers={{
                click: event => {
                  // Otherwise the map's own click handler runs next and
                  // clears the selection this just made.
                  L.DomEvent.stopPropagation(event);
                  onSelect(station.id);
                },
                dragend: event => {

                  const { lat, lng } = event.target.getLatLng();

                  onMove(station.id, lat, lng);
                }
              }}
            >

              <Tooltip direction="top" offset={[0, -8]}>
                {stationLabel(station)}
              </Tooltip>

            </Marker>
          );
        })
      }


      {
        searchPoint && (

          <Marker
            position={[searchPoint.latitude, searchPoint.longitude]}
            icon={SEARCH_ICON}
            // Not a station, so it must not answer a click as one.
            interactive={false}
          />
        )
      }


      {
        pendingPosition && (

          <Marker
            position={pendingPosition}
            icon={PENDING_ICON}
            draggable
            eventHandlers={{
              dragend: event => {

                const { lat, lng } = event.target.getLatLng();

                onPlace(lat, lng);
              }
            }}
          >
            <Tooltip direction="top" offset={[0, -10]}>
              New station
            </Tooltip>
          </Marker>
        )
      }

    </MapContainer>
  );
};
