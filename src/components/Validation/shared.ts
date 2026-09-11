import { VideoAssetName } from '../../types';


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
