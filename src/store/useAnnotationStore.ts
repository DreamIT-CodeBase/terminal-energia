import { create } from 'zustand';

import {
  Annotation,
  AnnotationClass,
  BoardBox,
  CanvasSettings,
  CapturedFrame,
  ImageItem,
  PriceRegion,
  Selection
} from '../types';


/**
 * One store for one video.
 *
 * The model is the video: frames captured from it, boards drawn on those
 * frames, price regions drawn inside each board. The canvas underneath is
 * deliberately generic -- it draws boxes over one image and knows nothing
 * about any of that -- so this store also publishes a canvas-shaped view of
 * whatever is selected, and routes the boxes the canvas produces back to the
 * right place.
 *
 * A board is not a separate image: it is its frame with a crop window
 * applied. Nothing is cut until the video is submitted, so moving a board box
 * simply re-frames the view instead of invalidating a file.
 */


interface ModelSnapshot {
  frames: CapturedFrame[];
  boards: BoardBox[];
  regions: PriceRegion[];
}


interface AnnotationState extends ModelSnapshot {
  projectName: string;
  videoId: string | null;
  // Length of the source clip, so the capture controls can offer a spread.
  durationMs: number;

  selection: Selection;

  classes: AnnotationClass[];

  // ----- canvas-facing view, derived from the model above -----
  images: ImageItem[];
  annotations: Annotation[];
  selectedImageId: string | null;
  selectedAnnotationId: string | null;
  canvasSettings: CanvasSettings;

  history: ModelSnapshot[];
  historyIndex: number;

  // ----- video -----
  loadVideo: (
    videoId: string,
    durationMs: number,
    frames: CapturedFrame[],
    boards: BoardBox[],
    regions: PriceRegion[]
  ) => void;
  clearProject: () => void;
  setDurationMs: (durationMs: number) => void;

  // ----- navigation -----
  select: (selection: Selection) => void;

  // ----- frames -----
  setFrames: (frames: CapturedFrame[]) => void;
  removeFrame: (frameName: string) => void;

  // ----- boards and regions -----
  setRegionPrice: (regionId: string, price: string) => void;

  // ----- canvas callbacks -----
  addAnnotation: (annotation: Annotation) => void;
  deleteAnnotation: (id: string) => void;
  updateAnnotationCoordinates: (id: string, coordinates: number[]) => void;
  setSelectedAnnotationId: (id: string | null) => void;
  setSelectedImageId: (id: string | null) => void;
  setImageDimensions: (imageId: string, width: number, height: number) => void;
  updateCanvasSettings: (settings: Partial<CanvasSettings>) => void;
  resetCanvas: () => void;

  // ----- history -----
  saveHistory: () => void;
  undo: () => void;
  redo: () => void;

  // ----- draft -----
  readDraft: (videoId: string) => ModelSnapshot | null;
  clearDraft: (videoId: string) => void;
}


// Fixed for the whole tool: there is only ever one project.
export const PROJECT_NAME = 'fuel-price-labelling';

export const CLASS_BOARD = 'class-board';
export const CLASS_PRICE_REGION = 'class-price-region';

export const DEFAULT_CLASSES: AnnotationClass[] = [
  { id: CLASS_BOARD, name: 'board', color: '#3B82F6' },            // Blue
  { id: CLASS_PRICE_REGION, name: 'price region', color: '#EF4444' } // Red
];

const INITIAL_SETTINGS: CanvasSettings = {
  zoom: 1,
  panX: 0,
  panY: 0,
  tool: 'bbox',
  gridVisible: false,
  opacity: 0.6,
  brushWidth: 2
};


// ============================================================
// IDS
// ============================================================

let idCounter = 0;

const nextId = (
  prefix: string
): string => {

  idCounter += 1;

  return `${prefix}-${idCounter.toString(36)}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;
};


export const frameImageId = (
  frameName: string
): string => `frame:${frameName}`;

export const boardImageId = (
  boardId: string
): string => `board:${boardId}`;


export const frameUrl = (
  videoId: string,
  frameName: string
): string =>
  `/api/videos/${encodeURIComponent(videoId)}` +
  `/frames/${encodeURIComponent(frameName)}/file`;


/** `t00012340.png` at 12.34 s reads as `0:12.340`. */
export const formatTimestamp = (
  timestampMs: number
): string => {

  const totalSeconds = Math.floor(timestampMs / 1000);

  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  const millis = timestampMs % 1000;

  return (
    `${minutes}:` +
    `${seconds.toString().padStart(2, '0')}.` +
    `${millis.toString().padStart(3, '0')}`
  );
};


/**
 * How a frame is named on screen. A capture is known by the moment it came
 * from; a pipeline frame only has its file name, so the extension is dropped
 * and the rest is shown as it is.
 */
export const frameLabel = (
  frame: CapturedFrame
): string => {

  if (frame.timestampMs !== null) {
    return formatTimestamp(frame.timestampMs);
  }

  return frame.name.replace(/\.[^.]+$/, '');
};


export const boardLabel = (
  boards: BoardBox[],
  board: BoardBox,
  frames: CapturedFrame[]
): string => {

  const frame = frames.find(item => item.name === board.frameName);

  const position =
    boards
      .filter(item => item.frameName === board.frameName)
      .findIndex(item => item.id === board.id) + 1;

  const at = frame ? frameLabel(frame) : board.frameName;

  return `${at} · board ${position}`;
};


// ============================================================
// DRAFTS
//
// Captured frames live in the working area on the server, so a
// draft only has to carry the boxes and prices that have not
// been submitted yet.
// ============================================================

const draftKey = (
  videoId: string
): string => `labelling_draft_${videoId}`;


const saveDraft = (
  videoId: string | null,
  boards: BoardBox[],
  regions: PriceRegion[]
) => {

  if (typeof window === 'undefined' || !videoId) return;

  try {
    localStorage.setItem(
      draftKey(videoId),
      JSON.stringify({ boards, regions })
    );

  } catch (error) {
    console.error('Failed to save draft:', error);
  }
};


// ============================================================
// THE CANVAS VIEW
//
// Turns whatever is selected into the one image the canvas
// draws, plus the boxes that belong on it.
// ============================================================

const buildView = (
  state: {
    videoId: string | null;
    selection: Selection;
    frames: CapturedFrame[];
    boards: BoardBox[];
    regions: PriceRegion[];
  }
): {
  images: ImageItem[];
  annotations: Annotation[];
  selectedImageId: string | null;
} => {

  const { videoId, selection, frames, boards, regions } = state;

  if (!videoId || selection.kind === 'video') {
    return { images: [], annotations: [], selectedImageId: null };
  }


  // --------------------------------------------------
  // A FRAME: BOARDS ARE DRAWN ON IT
  // --------------------------------------------------

  if (selection.kind === 'frame') {

    const frame = frames.find(item => item.name === selection.frameName);

    if (!frame) {
      return { images: [], annotations: [], selectedImageId: null };
    }

    const imageId = frameImageId(frame.name);

    const frameBoards = boards.filter(
      board => board.frameName === frame.name
    );

    return {
      images: [
        {
          id: imageId,
          name: frameLabel(frame),
          width: frame.width,
          height: frame.height,
          url: frameUrl(videoId, frame.name),
          progress: frameBoards.length > 0 ? 'in-progress' : 'todo'
        }
      ],
      annotations: frameBoards.map(board => ({
        id: board.id,
        imageId,
        classId: CLASS_BOARD,
        type: 'rect' as const,
        coordinates: board.coordinates
      })),
      selectedImageId: imageId
    };
  }


  // --------------------------------------------------
  // A BOARD: ITS FRAME, CLIPPED TO THE BOARD BOX
  // --------------------------------------------------

  const board = boards.find(item => item.id === selection.boardId);

  if (!board) {
    return { images: [], annotations: [], selectedImageId: null };
  }

  const frame = frames.find(item => item.name === board.frameName);

  if (!frame) {
    return { images: [], annotations: [], selectedImageId: null };
  }

  const imageId = boardImageId(board.id);

  const boardRegions = regions.filter(
    region => region.boardId === board.id
  );

  return {
    images: [
      {
        id: imageId,
        name: boardLabel(boards, board, frames),
        // The canvas works these out from the crop window and the natural
        // size of the frame it loads.
        width: 0,
        height: 0,
        url: frameUrl(videoId, frame.name),
        progress: boardRegions.length > 0 ? 'in-progress' : 'todo',
        cropBox: board.coordinates
      }
    ],
    annotations: boardRegions.map(region => ({
      id: region.id,
      imageId,
      classId: CLASS_PRICE_REGION,
      type: 'rect' as const,
      coordinates: region.coordinates
    })),
    selectedImageId: imageId
  };
};


const emptyModel: ModelSnapshot = {
  frames: [],
  boards: [],
  regions: []
};


export const useAnnotationStore = create<AnnotationState>((set, get) => {

  /**
   * Re-derives the canvas view after any change to the model or the
   * selection, so the two can never drift apart.
   */
  const rebuild = (
    patch: Partial<AnnotationState>
  ) => {

    const next = { ...get(), ...patch };

    set({
      ...patch,
      ...buildView(next)
    } as Partial<AnnotationState>);
  };


  return {
    projectName: PROJECT_NAME,
    videoId: null,
    durationMs: 0,

    frames: [],
    boards: [],
    regions: [],

    selection: { kind: 'video' },

    classes: DEFAULT_CLASSES,

    images: [],
    annotations: [],
    selectedImageId: null,
    selectedAnnotationId: null,
    canvasSettings: INITIAL_SETTINGS,

    history: [emptyModel],
    historyIndex: 0,


    // ============================================================
    // VIDEO
    // ============================================================

    loadVideo: (videoId, durationMs, frames, boards, regions) => {

      const selection: Selection =
        frames.length === 0
          ? { kind: 'video' }
          : { kind: 'frame', frameName: frames[0].name };

      const model = { frames, boards, regions };

      set({
        videoId,
        durationMs,
        ...model,
        selection,
        classes: DEFAULT_CLASSES,
        selectedAnnotationId: null,
        canvasSettings: INITIAL_SETTINGS,
        history: [JSON.parse(JSON.stringify(model))],
        historyIndex: 0,
        ...buildView({ videoId, selection, ...model })
      });
    },

    clearProject: () => {

      set({
        videoId: null,
        durationMs: 0,
        ...emptyModel,
        selection: { kind: 'video' },
        classes: DEFAULT_CLASSES,
        images: [],
        annotations: [],
        selectedImageId: null,
        selectedAnnotationId: null,
        canvasSettings: INITIAL_SETTINGS,
        history: [emptyModel],
        historyIndex: 0
      });
    },

    setDurationMs: (durationMs) => {
      set({ durationMs });
    },


    // ============================================================
    // NAVIGATION
    // ============================================================

    select: (selection) => {

      rebuild({
        selection,
        selectedAnnotationId: null,
        // Every view has its own natural fit, which the canvas recomputes
        // when the image loads.
        canvasSettings: { ...get().canvasSettings, zoom: 1, panX: 0, panY: 0 }
      });
    },


    // ============================================================
    // FRAMES
    // ============================================================

    setFrames: (frames) => {

      const known = new Set(frames.map(frame => frame.name));

      // A frame that is gone from the working area takes its boards, and
      // their regions, with it.
      const boards = get().boards.filter(
        board => known.has(board.frameName)
      );

      const boardIds = new Set(boards.map(board => board.id));

      const regions = get().regions.filter(
        region => boardIds.has(region.boardId)
      );

      const selection = get().selection;

      const stillValid =
        selection.kind === 'video' ||
        (selection.kind === 'frame' && known.has(selection.frameName)) ||
        (selection.kind === 'board' && boardIds.has(selection.boardId));

      rebuild({
        frames,
        boards,
        regions,
        selection: stillValid ? selection : { kind: 'video' }
      });

      get().saveHistory();
    },

    removeFrame: (frameName) => {

      const frames = get().frames.filter(
        frame => frame.name !== frameName
      );

      get().setFrames(frames);
    },


    // ============================================================
    // PRICES
    // ============================================================

    setRegionPrice: (regionId, price) => {

      const regions = get().regions.map(region =>
        region.id === regionId
          ? { ...region, price }
          : region
      );

      rebuild({ regions });

      // Typing a price is a change worth keeping, but not one worth an undo
      // step per keystroke, so the draft is written without a snapshot.
      saveDraft(get().videoId, get().boards, regions);
    },


    // ============================================================
    // BOXES DRAWN ON THE CANVAS
    //
    // Which list a box lands in is decided by what is selected,
    // never by the canvas.
    // ============================================================

    addAnnotation: (annotation) => {

      const { selection } = get();

      if (selection.kind === 'frame') {

        const board: BoardBox = {
          id: nextId('board'),
          frameName: selection.frameName,
          coordinates: annotation.coordinates
        };

        rebuild({
          boards: [...get().boards, board],
          selectedAnnotationId: board.id
        });

      } else if (selection.kind === 'board') {

        const region: PriceRegion = {
          id: nextId('region'),
          boardId: selection.boardId,
          coordinates: annotation.coordinates,
          price: ''
        };

        rebuild({
          regions: [...get().regions, region],
          selectedAnnotationId: region.id
        });

      } else {
        return;
      }

      get().saveHistory();
    },

    deleteAnnotation: (id) => {

      const { boards, regions, selection, selectedAnnotationId } = get();

      const isBoard = boards.some(board => board.id === id);

      if (isBoard) {

        const nextBoards = boards.filter(board => board.id !== id);

        // A board's regions have nowhere to live without it.
        const nextRegions = regions.filter(region => region.boardId !== id);

        rebuild({
          boards: nextBoards,
          regions: nextRegions,
          selectedAnnotationId:
            selectedAnnotationId === id ? null : selectedAnnotationId,
          selection:
            selection.kind === 'board' && selection.boardId === id
              ? { kind: 'video' }
              : selection
        });

      } else {

        rebuild({
          regions: regions.filter(region => region.id !== id),
          selectedAnnotationId:
            selectedAnnotationId === id ? null : selectedAnnotationId
        });
      }

      get().saveHistory();
    },

    updateAnnotationCoordinates: (id, coordinates) => {

      const { boards, regions } = get();

      if (boards.some(board => board.id === id)) {

        rebuild({
          boards: boards.map(board =>
            board.id === id
              ? { ...board, coordinates }
              : board
          )
        });

      } else {

        rebuild({
          regions: regions.map(region =>
            region.id === id
              ? { ...region, coordinates }
              : region
          )
        });
      }

      get().saveHistory();
    },

    setSelectedAnnotationId: (id) => {
      set({ selectedAnnotationId: id });
    },

    setSelectedImageId: (id) => {
      // The view holds exactly one image, so this only ever clears selection.
      set({ selectedImageId: id, selectedAnnotationId: null });
    },

    setImageDimensions: (imageId, width, height) => {

      const { frames, selection } = get();

      // Only a frame has a size worth recording; a board's size is a function
      // of its frame and its crop window.
      if (selection.kind !== 'frame') return;

      const target = frames.find(
        frame => frameImageId(frame.name) === imageId
      );

      if (!target || (target.width === width && target.height === height)) {
        return;
      }

      rebuild({
        frames: frames.map(frame =>
          frame.name === target.name
            ? { ...frame, width, height }
            : frame
        )
      });
    },

    updateCanvasSettings: (settings) => {
      set(state => ({
        canvasSettings: { ...state.canvasSettings, ...settings }
      }));
    },

    resetCanvas: () => {
      set(state => ({
        canvasSettings: {
          ...state.canvasSettings,
          zoom: 1,
          panX: 0,
          panY: 0
        }
      }));
    },


    // ============================================================
    // HISTORY
    // ============================================================

    saveHistory: () => {

      const { frames, boards, regions, history, historyIndex, videoId } = get();

      const snapshot: ModelSnapshot = JSON.parse(
        JSON.stringify({ frames, boards, regions })
      );

      const nextHistory = [
        ...history.slice(0, historyIndex + 1),
        snapshot
      ];

      // Bounded so a long session cannot grow without limit.
      if (nextHistory.length > 50) {
        nextHistory.shift();
      }

      set({
        history: nextHistory,
        historyIndex: nextHistory.length - 1
      });

      saveDraft(videoId, boards, regions);
    },

    undo: () => {

      const { history, historyIndex, videoId } = get();

      if (historyIndex <= 0) return;

      const snapshot = history[historyIndex - 1];

      rebuild({
        ...snapshot,
        historyIndex: historyIndex - 1,
        selectedAnnotationId: null
      });

      saveDraft(videoId, snapshot.boards, snapshot.regions);
    },

    redo: () => {

      const { history, historyIndex, videoId } = get();

      if (historyIndex >= history.length - 1) return;

      const snapshot = history[historyIndex + 1];

      rebuild({
        ...snapshot,
        historyIndex: historyIndex + 1,
        selectedAnnotationId: null
      });

      saveDraft(videoId, snapshot.boards, snapshot.regions);
    },


    // ============================================================
    // DRAFT
    // ============================================================

    readDraft: (videoId) => {

      if (typeof window === 'undefined') return null;

      try {
        const raw = localStorage.getItem(draftKey(videoId));

        if (!raw) return null;

        const parsed = JSON.parse(raw);

        if (
          !Array.isArray(parsed?.boards) ||
          !Array.isArray(parsed?.regions)
        ) {
          return null;
        }

        return {
          frames: [],
          boards: parsed.boards as BoardBox[],
          regions: parsed.regions as PriceRegion[]
        };

      } catch (error) {
        console.error('Failed to read draft:', error);
        return null;
      }
    },

    clearDraft: (videoId) => {
      if (typeof window === 'undefined') return;
      localStorage.removeItem(draftKey(videoId));
    }
  };
});
