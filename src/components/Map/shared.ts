import { Station } from '../../types';


// ============================================================
// STATION MAP HELPERS
//
// Shared by the workspace, the rail, the map and the edit panel.
// ============================================================

export const readError = async (
  response: Response
): Promise<string> => {

  try {
    const body = await response.json();

    if (typeof body?.error === 'string') return body.error;
    if (typeof body?.detail === 'string') return body.detail;

  } catch {
    // Fall through to the status code.
  }

  return `Request failed with status ${response.status}.`;
};


// ============================================================
// PROVENANCE
//
// An id carries where the station came from as a prefix. Nothing
// depends on it, but it is worth showing: a surveyed entry was
// stood in front of by a person, a detected one was inferred by a
// device that stopped somewhere unmapped.
// ============================================================

export type Provenance = 'surveyed' | 'detected' | 'provider';

/** New stations are entered by hand here, so they are surveyed. */
export const NEW_STATION_PREFIX = 'surveyed:';


export const provenanceOf = (
  id: string
): Provenance => {

  if (id.startsWith('surveyed:')) return 'surveyed';
  if (id.startsWith('detected:')) return 'detected';

  return 'provider';
};


export const PROVENANCE_LABEL: Record<Provenance, string> = {
  surveyed: 'Surveyed',
  detected: 'Detected',
  provider: 'Maps provider'
};


/**
 * One fill for every station, whatever its provenance. Colour is not used to
 * encode where a station came from: the panel says that in words, and a map of
 * a few hundred markers reads better as one even field than as three colours
 * competing over it.
 */
export const STATION_COLOUR = '#2563eb';


// ============================================================
// IDS
//
// A new station's id is derived from its name and shown before
// it is saved, because the id is the key: saving onto one that
// already exists would amend that station instead of adding one.
// ============================================================

/**
 * `Tartu Ringtee Neste` becomes `tartu-ringtee-neste`. Estonian names carry
 * diacritics (Räpina, Võru), which normalise to their base letters rather
 * than being dropped, so the slug stays readable.
 */
export const slugify = (
  value: string
): string =>
  value
    .normalize('NFD')
    // Strip the combining marks NFD just separated out.
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');


export const stationIdFromName = (
  name: string
): string => {

  const slug = slugify(name);

  return slug ? `${NEW_STATION_PREFIX}${slug}` : '';
};


// ============================================================
// DISPLAY
// ============================================================

export const stationLabel = (
  station: Station
): string =>
  station.name?.trim() || station.id;


export const hasPosition = (
  station: Station
): boolean =>
  typeof station.latitude === 'number' &&
  typeof station.longitude === 'number' &&
  Number.isFinite(station.latitude) &&
  Number.isFinite(station.longitude);


/** Six decimals is about 0.1m -- past what a forecourt position means. */
export const formatCoordinate = (
  value: number | null
): string =>
  typeof value === 'number' && Number.isFinite(value)
    ? value.toFixed(6)
    : '';


export const formatModified = (
  modifiedTime: number | null
): string => {

  if (!modifiedTime) return 'Never written here';

  return new Date(modifiedTime).toLocaleString();
};


/**
 * Ordered by name so the rail is scannable, with unnamed entries last rather
 * than sorted under whatever their id happens to start with.
 */
export const compareStations = (
  a: Station,
  b: Station
): number => {

  const na = a.name?.trim() ?? '';
  const nb = b.name?.trim() ?? '';

  if (!na !== !nb) return na ? -1 : 1;

  return (na || a.id).localeCompare(nb || b.id);
};


export const matchesQuery = (
  station: Station,
  query: string
): boolean => {

  const needle = query.trim().toLowerCase();

  if (!needle) return true;

  return [
    station.id,
    station.name,
    station.brand,
    station.note,
    // So a bare `58.39` narrows the rail the same way a name does. Full
    // precision is matched against, not the rounded display value, because
    // that is what someone pasting a coordinate out of the panel will have.
    formatCoordinate(station.latitude),
    formatCoordinate(station.longitude)
  ].some(
    field => field?.toLowerCase().includes(needle)
  );
};


// ============================================================
// COORDINATE SEARCH
//
// A full pair in the search box is a place, not a substring: it
// is somewhere to look, and what matters is which forecourts are
// near it. A single number stays a plain text match, handled by
// `matchesQuery` above -- half a coordinate is not a position.
// ============================================================

export interface Point {
  latitude: number;
  longitude: number;
}


/**
 * Reads `58.39867, 26.70944` out of the search box, comma or whitespace
 * separated, with an optional `N`/`S`/`E`/`W` suffix on either half so a
 * coordinate copied off a maps page pastes straight in.
 *
 * Returns null for anything that is not a complete, in-range pair, which is
 * what keeps a station named "95" or a half-typed number out of this path.
 */
export const parseCoordinates = (
  query: string
): Point | null => {

  const match = query
    .trim()
    .match(
      /^(-?\d+(?:\.\d+)?)\s*([NSns])?\s*[, ]\s*(-?\d+(?:\.\d+)?)\s*([EWew])?$/
    );

  if (!match) return null;

  const [, rawLat, latHemisphere, rawLon, lonHemisphere] = match;

  let latitude = Number(rawLat);
  let longitude = Number(rawLon);

  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;

  // A hemisphere letter wins over the sign, so `58.4 S` is the southern
  // reading rather than being silently taken as northern.
  if (latHemisphere?.toUpperCase() === 'S') latitude = -Math.abs(latitude);
  if (lonHemisphere?.toUpperCase() === 'W') longitude = -Math.abs(longitude);

  if (latitude < -90 || latitude > 90) return null;
  if (longitude < -180 || longitude > 180) return null;

  return { latitude, longitude };
};


const EARTH_RADIUS_METRES = 6371000;

const toRadians = (
  degrees: number
): number =>
  (degrees * Math.PI) / 180;


/**
 * Great-circle distance. Estonia is small enough that a flat approximation
 * would do, but this costs nothing and does not fall apart if the document
 * ever holds a station somewhere else.
 */
export const distanceMetres = (
  from: Point,
  to: Point
): number => {

  const deltaLat = toRadians(to.latitude - from.latitude);
  const deltaLon = toRadians(to.longitude - from.longitude);

  const a =
    Math.sin(deltaLat / 2) ** 2 +
    Math.cos(toRadians(from.latitude)) *
      Math.cos(toRadians(to.latitude)) *
      Math.sin(deltaLon / 2) ** 2;

  return (
    EARTH_RADIUS_METRES *
    2 *
    Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  );
};


export const stationPoint = (
  station: Station
): Point | null =>
  hasPosition(station)
    ? {
        latitude: station.latitude as number,
        longitude: station.longitude as number
      }
    : null;


/** Rounded to something a person reads, not to survey precision. */
export const formatDistance = (
  metres: number
): string => {

  if (metres < 1000) {
    return `${Math.round(metres)} m`;
  }

  const km = metres / 1000;

  return `${km < 10 ? km.toFixed(1) : Math.round(km)} km`;
};


// ============================================================
// FUEL TYPES
//
// The order is the board's row order, top to bottom, and it is
// data: Räpina Olerex lists 98 before 95. It is never sorted --
// the panel moves a row only when someone drags it.
// ============================================================

/** The codes seen in the stored document, offered as quick adds. */
export const COMMON_FUEL_TYPES = ['95', '98', 'D', 'MYD', 'LPG'];


export const moveItem = <T,>(
  items: T[],
  from: number,
  to: number
): T[] => {

  if (
    from === to ||
    from < 0 ||
    to < 0 ||
    from >= items.length ||
    to >= items.length
  ) {
    return items;
  }

  const next = [...items];

  const [moved] = next.splice(from, 1);

  next.splice(to, 0, moved);

  return next;
};


// ============================================================
// ESTONIA
//
// Where the map opens when there is nothing to fit: every stored
// station is in Estonia, but an empty document must still land
// somewhere sensible.
// ============================================================

export const DEFAULT_CENTRE: [number, number] = [58.5953, 25.0136];

export const DEFAULT_ZOOM = 7;

/** Close enough to place a marker on the right forecourt. */
export const FOCUS_ZOOM = 17;

/**
 * Where a coordinate search lands. Deliberately wider than FOCUS_ZOOM: the
 * question being asked is "what is near here", so the neighbours have to stay
 * on screen.
 */
export const SEARCH_ZOOM = 13;
