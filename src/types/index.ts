export type VideoFolder = 'frames' | 'crops' | 'prepped';

/**
 * Labelling runs down one video: frames are captured from it, boards are
 * drawn on those frames, and price regions are drawn inside each board.
 * The section names double as the three things a box can belong to.
 */
export type WorkspaceSection = 'video' | 'frames' | 'boards';


// ============================================================
// THE LABELLING MODEL
//
// One video at a time. Everything below hangs off it.
// ============================================================

/**
 * Where a frame came from. `source` is what the pipeline already extracted
 * into `video-id/frames/`, which is read only; `captured` is what the labeller
 * took out of the clip.
 */
export type FrameOrigin = 'source' | 'captured';


/** One frame a video offers for labelling. */
export interface CapturedFrame {
  // File name. A capture is `t00012340.png`, derived from its timestamp, so
  // taking the same moment twice is not a duplicate. A pipeline frame is
  // whatever the pipeline called it, usually `frame_00007.png`.
  name: string;
  path: string;
  origin: FrameOrigin;
  // Only a capture knows the moment it came from: a pipeline frame is named
  // after a frame index, which cannot be turned back into a time reliably.
  timestampMs: number | null;
  // Natural pixel size, filled in by the API where it knows it and confirmed
  // by the canvas once the frame is drawn.
  width: number;
  height: number;
  size: number;
}


/** A price board, boxed on a captured frame. */
export interface BoardBox {
  id: string;
  frameName: string;
  // Normalised [x, y, w, h] against the frame, x and y at the top-left.
  coordinates: number[];
}


/** One price on a board, boxed inside that board's crop. */
export interface PriceRegion {
  id: string;
  boardId: string;
  // Normalised [x, y, w, h] against the BOARD CROP, not the frame. The board
  // view draws the frame clipped to the board box, so this is what the canvas
  // produces directly and what the price-region dataset wants.
  coordinates: number[];
  // Held as typed so a half-entered value survives; empty means the labeller
  // could not read it off the board, which is allowed.
  price: string;
}


/**
 * What the centre of the workspace is showing. The three sidebar sections
 * navigate between these.
 */
export type Selection =
  | { kind: 'video' }
  | { kind: 'frame'; frameName: string }
  | { kind: 'board'; boardId: string };


// ============================================================
// CANVAS
//
// The canvas is deliberately generic: it draws boxes over one
// image. The store adapts the model above into this shape, so
// a board is just its frame with a crop window applied.
// ============================================================

export interface ImageItem {
  id: string;
  name: string;
  width: number;
  height: number;
  url: string;
  progress: 'todo' | 'in-progress' | 'done';
  /**
   * Normalised [x, y, w, h] window into the source image. Set for a board:
   * the canvas shows only that part of the frame, and boxes drawn on it are
   * normalised against the window rather than the whole frame.
   */
  cropBox?: number[];
}

export interface AnnotationClass {
  id: string;
  name: string;
  color: string; // Hex color string
}

// Boxes are the only shape the detector trains on.
export type AnnotationType = 'rect';

export interface Annotation {
  id: string;
  imageId: string;
  classId: string;
  type: AnnotationType;
  // Normalized coordinates (0 to 1) relative to image size:
  // [x, y, w, h], with x, y at the top-left.
  coordinates: number[];
}

export interface CanvasSettings {
  zoom: number;
  panX: number;
  panY: number;
  tool: 'bbox' | 'select' | 'pan';
  gridVisible: boolean;
  opacity: number;
  brushWidth: number;
}


// ============================================================
// AZURE BLOB STORAGE
// ============================================================

export interface VideoAssetName {
  name: string;
  path: string;
  size: number;
}

/** Wire format from the system API, which is snake_case. */
export interface VideoAssets {
  video_id: string;
  folders: Record<VideoFolder, VideoAssetName[]>;
  has_api_response: boolean;
  video_path: string | null;
}

export interface VideoSummary {
  videoId: string;
  annotated: boolean;
  // Submitted, and so filed into a week. Finished videos drop off the list.
  completed: boolean;
  // Frames are captured from video.mp4, so a video without one cannot be
  // labelled and is hidden.
  hasVideo: boolean;
  imageCount: number;
}

/** What the API reports about the source clip, before it is played. */
export interface VideoProbe {
  video_id: string;
  duration_ms: number;
  width: number;
  height: number;
  fps: number | null;
}

/** Wire format of one frame. */
export interface CapturedFrameRecord {
  name: string;
  path: string;
  origin: FrameOrigin;
  timestamp_ms: number | null;
  width: number;
  height: number;
  size: number;
}

export interface SubmitResult {
  video_id: string;
  week: string | null;
  frames_written: number;
  crops_written: number;
  regions_written: number;
  boards_without_regions: number;
  skipped: string[];
  saved_at: string;
}


// ============================================================
// STATION MAPPING
//
// The forecourts the fleet drives to, edited on /map and stored
// as one JSON document behind the system API. Field names are the
// wire names, snake_case, so a record round-trips unchanged.
// ============================================================

/** One forecourt as stored. */
export interface Station {
  // The key. Ids carry their provenance as a prefix: `surveyed:` for one
  // entered by hand, `detected:` for one a device found. A bare id came from
  // the maps provider. Nothing parses the prefix; it is there to be read.
  id: string;
  name: string | null;
  latitude: number | null;
  longitude: number | null;
  // Operator, as the maps provider spells it.
  brand: string | null;
  // Epoch milliseconds, stamped by the server on every write.
  modified_time: number | null;
  // Top-to-bottom order of the rows on the price board, so a code's position
  // in this list is its row number. Never sort it. Absent when nobody has
  // surveyed the board on foot, which is most stations.
  fuel_types: string[] | null;
  note: string | null;
}

export interface StationListResponse {
  version: number;
  count: number;
  // Changes whenever the stored document does.
  etag: string | null;
  stations: Station[];
}

/**
 * The fields to write. Anything omitted keeps its stored value: the API
 * merges rather than replaces, so an edit here cannot blank out a field it
 * never showed.
 */
export interface StationWrite {
  name?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  brand?: string | null;
  fuel_types?: string[] | null;
  note?: string | null;
}

export interface StationUpsertResponse {
  created: boolean;
  station: Station;
  etag: string | null;
  count: number;
}

export interface StationDeleteResponse {
  deleted: boolean;
  // The entry as it stood. Nothing else keeps a copy.
  station: Station;
  etag: string | null;
  count: number;
}
