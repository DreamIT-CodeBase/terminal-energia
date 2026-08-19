/**
 * Pulls the capture details out of `video-id/api_response.json`.
 *
 * The file is written by the detection pipeline, so it is read defensively:
 * a missing or reshaped field leaves that part of the metadata undefined
 * rather than breaking the canvas.
 */

export interface VideoMetadata {
  timestamp?: string;
  latitude?: number;
  longitude?: number;
  gpsAccuracyM?: number;
}


const asRecord = (
  value: unknown
): Record<string, unknown> | null => {

  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value)
  )
    ? value as Record<string, unknown>
    : null;
};


const asFiniteNumber = (
  value: unknown
): number | undefined => {

  // Coordinates occasionally arrive as strings.
  const numeric =
    typeof value === 'string'
      ? Number(value)
      : value;

  return typeof numeric === 'number' && Number.isFinite(numeric)
    ? numeric
    : undefined;
};


export const readVideoMetadata = (
  apiResponse: unknown
): VideoMetadata => {

  const root = asRecord(apiResponse);

  if (!root) {
    return {};
  }

  const location = asRecord(root.location);

  return {
    timestamp:
      typeof root.timestamp === 'string' && root.timestamp
        ? root.timestamp
        : undefined,

    latitude: asFiniteNumber(location?.latitude),
    longitude: asFiniteNumber(location?.longitude),
    gpsAccuracyM: asFiniteNumber(location?.gps_accuracy_m)
  };
};


// Capture times arrive in UTC but are read by people working in Estonia, so
// they are shown in Tallinn time. `sv-SE` formats as `YYYY-MM-DD HH:mm:ss`,
// and the runtime's zone data handles the EET/EEST switch across DST.
const TALLINN_TIME_ZONE = 'Europe/Tallinn';

let tallinnFormatter: Intl.DateTimeFormat | null = null;


const getTallinnFormatter = (): Intl.DateTimeFormat | null => {

  if (tallinnFormatter) {
    return tallinnFormatter;
  }

  try {
    tallinnFormatter = new Intl.DateTimeFormat('sv-SE', {
      timeZone: TALLINN_TIME_ZONE,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
      timeZoneName: 'short'
    });

  } catch {
    // A runtime without the zone data falls back to the raw value.
    return null;
  }

  return tallinnFormatter;
};


/**
 * `2026-08-16T17:48:10Z` becomes `2026-08-16 20:48:10 EEST` — the same instant
 * in Estonian local time. Anything unparseable is shown as it arrived.
 */
export const formatTimestamp = (
  timestamp: string
): string => {

  const parsed = new Date(timestamp);

  if (Number.isNaN(parsed.getTime())) {
    return timestamp;
  }

  const formatter = getTallinnFormatter();

  if (!formatter) {
    return timestamp;
  }

  return formatter.format(parsed);
};


export const formatCoordinates = (
  latitude: number,
  longitude: number
): string => {

  // Six decimals is roughly 0.1 m, finer than the GPS accuracy ever is.
  return `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`;
};
