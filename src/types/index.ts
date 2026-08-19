export type VideoFolder = 'frames' | 'crops' | 'prepped';

/**
 * Labelling runs in two passes over a video: boards are drawn on the full
 * frames, then the crops those boards produce are labelled for price regions.
 */
export type WorkflowStage = 'board' | 'price_region';

export interface ImageItem {
  id: string;
  name: string;
  width: number;
  height: number;
  url: string; // Object URL or base64
  progress: 'todo' | 'in-progress' | 'done';
  workflowStage?: WorkflowStage;
  // Azure Blob Storage origin, set for every image loaded from `debugdirectory`.
  blobPath?: string;
  folder?: VideoFolder;
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
  // Both passes submitted. Finished videos are dropped from the list.
  completed: boolean;
  // Many videos in the debug directory hold only JSON and no extracted
  // frames, so the count decides which one is worth opening.
  imageCount: number;
}

/**
 * Annotation shape as it is stored in `debugdirectoryannotated`. It drops the
 * client-side image id in favour of the blob path so a reload can rebuild the
 * project from storage alone.
 */
export type AnnotationRecord = Omit<Annotation, 'id' | 'imageId'>;

export interface SavedAnnotationImage {
  name: string;
  path: string;
  folder: VideoFolder;
  width: number;
  height: number;
  annotations: AnnotationRecord[];
}

/**
 * `video-id/annotations.json` exactly as the system API writes it: snake_case
 * at the file level, camelCase inside the annotation records.
 */
export interface SavedAnnotationFile {
  video_id: string;
  saved_at: string;
  saved_by: string;
  classes: AnnotationClass[];
  images: SavedAnnotationImage[];
}

/**
 * Where a video sits in the two-pass flow, as reported by the system API.
 * `crops` are the images stage 2 annotates.
 */
export interface StageState {
  video_id: string;
  stage: WorkflowStage;
  board_submitted: boolean;
  price_region_submitted: boolean;
  crops: VideoAssetName[];
}
