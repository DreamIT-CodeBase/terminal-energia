import { VideoAssetName } from '../../types';

export interface EnrichedFuelType {
  row_num: number;
  fuel_type: string;
}

export interface EnrichedStation {
  latitude: number | null;
  longitude: number | null;
  name?: string | null;
  fuel_types?: Array<string | EnrichedFuelType> | null;
}

export const STATION_COORDINATE_TOLERANCE = 0.001;


// ============================================================
// VALIDATION HELPERS
//
// Small pieces shared by the validation workspace and its panes.
// The images this page validates are the board CROPS the pipeline
// cut from the video: each is one board seen at one moment, named
// after the source frame, time and detection confidence.
// ============================================================

/**
 * The earliest capture day this page shows. A video id starts with its day
 * (`20260910-122718-0b1a`), so the leading eight digits are the folder date and
 * a run qualifies when that date is this day or later. Everything before it is
 * hidden; new days appear on their own as runs land.
 */
export const VALIDATION_FROM_DATE = '20260909';


/**
 * Runs to keep off the UI even though they sit in a validation folder. Hand
 * picked, so the set is a plain lookup by exact video id.
 */
export const EXCLUDED_VIDEO_IDS = new Set([
  '20260910-054903-4cc8',
  '20260910-050447-0f1e',
  '20260910-041628-7bc7',
  '20260910-041147-16a1',
  '20260910-035735-7375',
  '20260910-024724-fb66',
  '20260910-021324-ec78',
  '20260910-013219-6152',
  '20260910-004758-699d',
  '20260909-215319-918f',
  '20260909-205005-bb80',
  '20260909-202622-0ca0',
  '20260909-191656-8de6',
  '20260909-184531-5a9e',
  '20260909-183905-bfee',
  '20260909-180015-3a08',
  '20260909-175031-96d2'
]);


/** The capture day of a run: the leading eight digits of its id. */
export const folderDate = (
  videoId: string
): string =>
  videoId.slice(0, 8);


export const belongsToValidationFolder = (
  videoId: string
): boolean =>
  // Equal length, all digits, so a string compare is a date compare.
  folderDate(videoId) >= VALIDATION_FROM_DATE &&
  !EXCLUDED_VIDEO_IDS.has(videoId);


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


const isFiniteNumber = (
  value: unknown
): value is number =>
  typeof value === 'number' && Number.isFinite(value);


const responseCoordinates = (
  response: unknown
): { latitude: number; longitude: number } | null => {

  if (response === null || typeof response !== 'object') return null;

  const root = response as Record<string, unknown>;
  const station =
    root.station !== null && typeof root.station === 'object'
      ? root.station as Record<string, unknown>
      : null;
  const location =
    root.location !== null && typeof root.location === 'object'
      ? root.location as Record<string, unknown>
      : null;

  const candidates = [station, location, root];

  for (const candidate of candidates) {
    if (
      candidate &&
      isFiniteNumber(candidate.latitude) &&
      isFiniteNumber(candidate.longitude)
    ) {
      return {
        latitude: candidate.latitude,
        longitude: candidate.longitude
      };
    }
  }

  return null;
};


/**
 * Validation responses normally carry the matched forecourt under `station`.
 * Older responses may only carry capture GPS coordinates under `location`, or
 * coordinates at the response root. A station is considered the same
 * forecourt when both coordinates are within 0.001 degrees. The first
 * coordinate match in the stored list wins.
 */
export const findStationByCoordinates = <T extends {
  latitude: number | null;
  longitude: number | null;
}>(
  response: unknown,
  stations: T[],
  tolerance = STATION_COORDINATE_TOLERANCE
): T | null => {

  const coordinates = responseCoordinates(response);

  if (!coordinates) return null;

  return stations.find(station =>
    isFiniteNumber(station.latitude) &&
    isFiniteNumber(station.longitude) &&
    Math.abs(station.latitude - coordinates.latitude) <= tolerance &&
    Math.abs(station.longitude - coordinates.longitude) <= tolerance
  ) ?? null;
};


export const stationNameFromResponse = (
  response: unknown
): string | null => {

  if (response === null || typeof response !== 'object') return null;

  const station = (response as Record<string, unknown>).station;

  if (station === null || typeof station !== 'object') return null;

  const name = (station as Record<string, unknown>).name;

  return typeof name === 'string' && name.trim() !== ''
    ? name.trim()
    : null;
};


export const coordinatesFromResponse = responseCoordinates;


const hasRows = (
  value: unknown
): value is Record<string, unknown> => {

  if (value === null || typeof value !== 'object') return false;

  const fuelPrices = (value as Record<string, unknown>).fuel_prices;

  return Array.isArray(fuelPrices) && fuelPrices.length > 0;
};


/**
 * Prefill fuel names on the first board that has rows. When the detector
 * returns a different number of rows, known names are applied top-to-bottom
 * until either list runs out; the remaining response values stay editable.
 */
export const applyStationFuelTypes = (
  response: unknown,
  fuelTypes: Array<string | EnrichedFuelType> | null | undefined
): unknown => {

  if (!Array.isArray(fuelTypes) || response === null || typeof response !== 'object') {
    return response;
  }

  const root = JSON.parse(JSON.stringify(response)) as Record<string, unknown>;
  const boards = root.boards;

  if (!Array.isArray(boards)) return response;

  const board = boards.find(hasRows);

  if (!board) return response;

  const rows = board.fuel_prices;

  if (!Array.isArray(rows)) return response;

  const namesByRow = new Map(
    fuelTypes.map((item, index) => [
      typeof item === 'string' ? index + 1 : item.row_num,
      typeof item === 'string' ? item : item.fuel_type
    ])
  );

  rows.forEach((row, index) => {
    if (row === null || typeof row !== 'object') return;

    const rowObject = row as Record<string, unknown>;
    const responseRow = typeof rowObject.row_num === 'number'
      ? rowObject.row_num
      : index + 1;
    const fuelType = namesByRow.get(responseRow);

    if (fuelType !== undefined) {
      rowObject.fuel_type = fuelType;
    }
  });

  return root;
};


export const firstBoardWithFuelPriceRowsIndex = (
  response: unknown
): number => {

  if (response === null || typeof response !== 'object') return -1;

  const boards = (response as Record<string, unknown>).boards;

  return Array.isArray(boards)
    ? boards.findIndex(hasRows)
    : -1;
};


/** Session-protected bytes of one crop, streamed through the Next server. */
export const cropFileUrl = (
  videoId: string,
  cropName: string
): string =>
  `/api/videos/${encodeURIComponent(videoId)}` +
  `/file/crops/${encodeURIComponent(cropName)}`;


// ============================================================
// CROP FILENAME
//
// A crop name carries everything needed to line it up against the
// response: which source frame it came from, at what second, which
// board and detection, and the detector's confidence. Reading it
// out lets the grid caption say more than a raw file name.
//
//   crop_f00006_b02_d00_t0000.40s_c0.68_o65x145.png
// ============================================================

export interface CropMeta {
  frame: number | null;
  board: number | null;
  timeS: number | null;
  confidence: number | null;
}


const numberFrom = (
  name: string,
  pattern: RegExp
): number | null => {

  const match = name.match(pattern);

  return match ? Number(match[1]) : null;
};


export const parseCropMeta = (
  name: string
): CropMeta => ({
  frame: numberFrom(name, /_f(\d+)/),
  board: numberFrom(name, /_b(\d+)/),
  timeS: numberFrom(name, /_t([\d.]+)s/),
  confidence: numberFrom(name, /_c([\d.]+)/)
});


/**
 * Chronological order: by source frame index, then by the raw name so the
 * order is stable when two crops share a frame.
 */
export const compareCrops = (
  a: VideoAssetName,
  b: VideoAssetName
): number => {

  const fa = parseCropMeta(a.name).frame ?? Number.MAX_SAFE_INTEGER;
  const fb = parseCropMeta(b.name).frame ?? Number.MAX_SAFE_INTEGER;

  if (fa !== fb) return fa - fb;

  return a.name.localeCompare(b.name);
};
