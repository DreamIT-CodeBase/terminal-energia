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

/** A moment taken out of the source clip and stored in the working area. */
export interface CapturedFrame {
  // File name in the working area, e.g. `t00012340.png`. Derived from the
  // timestamp, so capturing the same moment twice is not a duplicate.
  name: string;
  path: string;
  timestampMs: number;
  // Natural pixel size, filled in by the API and confirmed by the canvas.
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

/** Wire format of one captured frame. */
export interface CapturedFrameRecord {
  name: string;
  path: string;
  timestamp_ms: number;
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
