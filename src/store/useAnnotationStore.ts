import { create } from 'zustand';
import { ImageItem, AnnotationClass, Annotation, CanvasSettings, WorkflowStage } from '../types';

interface HistoryState {
  images: ImageItem[];
  classes: AnnotationClass[];
  annotations: Annotation[];
}

interface AnnotationState {
  projectName: string;
  // The video id currently open. There is only ever one project, so the video
  // is what scopes images, annotations and the local draft.
  videoId: string | null;
  // Labelling runs in two passes over a video: boards on the full frames,
  // then price regions on the crops those boards produced.
  stage: WorkflowStage;
  images: ImageItem[];
  classes: AnnotationClass[];
  annotations: Annotation[];
  selectedImageId: string | null;
  selectedAnnotationId: string | null;
  canvasSettings: CanvasSettings;

  // History Stack
  history: HistoryState[];
  historyIndex: number;

  // Actions
  setProjectName: (name: string) => void;
  setStage: (stage: WorkflowStage) => void;
  setImages: (images: ImageItem[]) => void;
  addImage: (image: ImageItem) => void;
  deleteImage: (id: string) => void;
  renameImage: (id: string, name: string) => void;
  setSelectedImageId: (id: string | null) => void;

  setClasses: (classes: AnnotationClass[]) => void;
  addClass: (cls: AnnotationClass) => void;
  deleteClass: (id: string) => void;
  renameClass: (id: string, name: string) => void;
  updateClassColor: (id: string, color: string) => void;

  addAnnotation: (ann: Annotation) => void;
  deleteAnnotation: (id: string) => void;
  updateAnnotationCoordinates: (id: string, coordinates: number[]) => void;
  updateAnnotationClass: (id: string, classId: string) => void;
  setSelectedAnnotationId: (id: string | null) => void;

  updateCanvasSettings: (settings: Partial<CanvasSettings>) => void;
  resetCanvas: () => void;

  // History actions
  saveHistory: () => void;
  undo: () => void;
  redo: () => void;

  // Global operations
  clearProject: () => void;
  importProject: (classes: AnnotationClass[], annotations: Annotation[], images?: ImageItem[]) => void;
  setImageProgress: (imageId: string, progress: 'todo' | 'in-progress' | 'done') => void;
  setImageDimensions: (imageId: string, width: number, height: number) => void;

  // Azure backed video loading
  loadVideoProject: (
    videoId: string,
    stage: WorkflowStage,
    images: ImageItem[],
    annotations: Annotation[]
  ) => void;
  readDraft: (videoId: string, stage: WorkflowStage) => Annotation[] | null;
  clearDraft: (videoId: string, stage: WorkflowStage) => void;
}

// Fixed for the whole tool: there is only ever one project.
export const PROJECT_NAME = 'fuel-price-labelling';

export const DEFAULT_CLASSES: AnnotationClass[] = [
  { id: 'class-board', name: 'board', color: '#3B82F6' },       // Blue
  { id: 'class-price-region', name: 'price region', color: '#EF4444' } // Red
];

const INITIAL_SETTINGS: CanvasSettings = {
  zoom: 1,
  panX: 0,
  panY: 0,
  tool: 'select',
  gridVisible: false,
  opacity: 0.6,
  brushWidth: 2
};

// Drafts are scoped per video so switching videos never mixes annotations.
// Azure remains the source of truth; this only protects unsaved work from a
// page reload.
const draftKey = (videoId: string, stage: WorkflowStage) =>
  `labelling_draft_${videoId}_${stage}`;

const saveDraft = (
  videoId: string | null,
  stage: WorkflowStage,
  annotations: Annotation[]
) => {
  if (typeof window === 'undefined' || !videoId) return;
  try {
    localStorage.setItem(
      draftKey(videoId, stage),
      JSON.stringify({ annotations })
    );
  } catch (e) {
    console.error('Failed to save draft:', e);
  }
};

export const useAnnotationStore = create<AnnotationState>((set, get) => ({
  projectName: PROJECT_NAME,
  videoId: null,
  stage: 'board',
  images: [],
  classes: DEFAULT_CLASSES,
  annotations: [],
  selectedImageId: null,
  selectedAnnotationId: null,
  canvasSettings: INITIAL_SETTINGS,

  // History Stack
  history: [
    {
      images: [],
      classes: DEFAULT_CLASSES,
      annotations: []
    }
  ],
  historyIndex: 0,

  // Save history snapshot
  saveHistory: () => {
    const { images, classes, annotations, history, historyIndex, videoId } = get();
    const cleanHistory = history.slice(0, historyIndex + 1);

    const newSnapshot: HistoryState = {
      images: JSON.parse(JSON.stringify(images)),
      classes: JSON.parse(JSON.stringify(classes)),
      annotations: JSON.parse(JSON.stringify(annotations))
    };

    // Limit history stack size to 50
    const nextHistory = [...cleanHistory, newSnapshot];
    if (nextHistory.length > 50) {
      nextHistory.shift();
    }

    set({
      history: nextHistory,
      historyIndex: nextHistory.length - 1
    });

    saveDraft(videoId, get().stage, annotations);
  },

  // Undo Action
  undo: () => {
    const { history, historyIndex, videoId } = get();
    if (historyIndex > 0) {
      const prevIndex = historyIndex - 1;
      const snapshot = history[prevIndex];
      set({
        images: snapshot.images,
        classes: snapshot.classes,
        annotations: snapshot.annotations,
        historyIndex: prevIndex,
        selectedAnnotationId: null // Reset selection to avoid referencing deleted items
      });
      saveDraft(videoId, get().stage, snapshot.annotations);
    }
  },

  // Redo Action
  redo: () => {
    const { history, historyIndex, videoId } = get();
    if (historyIndex < history.length - 1) {
      const nextIndex = historyIndex + 1;
      const snapshot = history[nextIndex];
      set({
        images: snapshot.images,
        classes: snapshot.classes,
        annotations: snapshot.annotations,
        historyIndex: nextIndex,
        selectedAnnotationId: null
      });
      saveDraft(videoId, get().stage, snapshot.annotations);
    }
  },

  setProjectName: (name) => {
    set({ projectName: name });
  },

  setStage: (stage) => {
    set({ stage, selectedAnnotationId: null });
  },

  setImages: (images) => {
    set({
      images,
      selectedImageId: images.length > 0 ? images[0].id : null,
      selectedAnnotationId: null
    });
    get().saveHistory();
  },

  addImage: (image) => {
    const { images } = get();
    const exists = images.find(img => img.name === image.name);
    if (exists) return; // Avoid duplicate names

    set((state) => {
      const newImages = [...state.images, image];
      return {
        images: newImages,
        selectedImageId: state.selectedImageId ? state.selectedImageId : image.id
      };
    });
    get().saveHistory();
  },

  deleteImage: (id) => {
    const { images, selectedImageId, annotations } = get();
    const filteredImages = images.filter(img => img.id !== id);
    const filteredAnnotations = annotations.filter(ann => ann.imageId !== id);

    let nextSelectedId = selectedImageId;
    if (selectedImageId === id) {
      nextSelectedId = filteredImages.length > 0 ? filteredImages[0].id : null;
    }

    set({
      images: filteredImages,
      annotations: filteredAnnotations,
      selectedImageId: nextSelectedId,
      selectedAnnotationId: null
    });
    get().saveHistory();
  },

  renameImage: (id, name) => {
    set((state) => ({
      images: state.images.map(img => img.id === id ? { ...img, name } : img)
    }));
    get().saveHistory();
  },

  setSelectedImageId: (id) => {
    set({ selectedImageId: id, selectedAnnotationId: null });
  },

  setClasses: (classes) => {
    set({ classes });
    get().saveHistory();
  },

  addClass: (cls) => {
    set((state) => ({
      classes: [...state.classes, cls]
    }));
    get().saveHistory();
  },

  deleteClass: (id) => {
    const { classes, annotations } = get();
    // Re-map or delete annotations associated with this class. Let's delete them.
    const filteredClasses = classes.filter(c => c.id !== id);
    const filteredAnnotations = annotations.filter(ann => ann.classId !== id);

    set({
      classes: filteredClasses,
      annotations: filteredAnnotations,
      selectedAnnotationId: null
    });
    get().saveHistory();
  },

  renameClass: (id, name) => {
    set((state) => ({
      classes: state.classes.map(c => c.id === id ? { ...c, name } : c)
    }));
    get().saveHistory();
  },

  updateClassColor: (id, color) => {
    set((state) => ({
      classes: state.classes.map(c => c.id === id ? { ...c, color } : c)
    }));
    get().saveHistory();
  },

  addAnnotation: (ann) => {
    set((state) => ({
      annotations: [...state.annotations, ann],
      selectedAnnotationId: ann.id
    }));

    // Automatically update image progress to in-progress or done
    const { selectedImageId } = get();
    if (selectedImageId) {
      get().setImageProgress(selectedImageId, 'in-progress');
    }
    get().saveHistory();
  },

  deleteAnnotation: (id) => {
    const { annotations, selectedAnnotationId, selectedImageId } = get();
    const filteredAnnotations = annotations.filter(ann => ann.id !== id);

    set({
      annotations: filteredAnnotations,
      selectedAnnotationId: selectedAnnotationId === id ? null : selectedAnnotationId
    });

    // If no annotations remain for this image, mark progress as todo
    if (selectedImageId) {
      const remainingForImage = filteredAnnotations.filter(ann => ann.imageId === selectedImageId);
      if (remainingForImage.length === 0) {
        get().setImageProgress(selectedImageId, 'todo');
      }
    }
    get().saveHistory();
  },

  updateAnnotationCoordinates: (id, coordinates) => {
    set((state) => ({
      annotations: state.annotations.map(ann => ann.id === id ? { ...ann, coordinates } : ann)
    }));
    get().saveHistory();
  },

  updateAnnotationClass: (id, classId) => {
    set((state) => ({
      annotations: state.annotations.map(ann => ann.id === id ? { ...ann, classId } : ann)
    }));
    get().saveHistory();
  },

  setSelectedAnnotationId: (id) => {
    set({ selectedAnnotationId: id });
  },

  updateCanvasSettings: (settings) => {
    set((state) => ({
      canvasSettings: { ...state.canvasSettings, ...settings }
    }));
  },

  resetCanvas: () => {
    set((state) => ({
      canvasSettings: {
        ...state.canvasSettings,
        zoom: 1,
        panX: 0,
        panY: 0
      }
    }));
  },

  clearProject: () => {
    set({
      videoId: null,
      stage: 'board',
      images: [],
      classes: DEFAULT_CLASSES,
      annotations: [],
      selectedImageId: null,
      selectedAnnotationId: null,
      canvasSettings: INITIAL_SETTINGS,
      history: [
        {
          images: [],
          classes: DEFAULT_CLASSES,
          annotations: []
        }
      ],
      historyIndex: 0
    });
  },

  importProject: (classes, annotations, images) => {
    const currentImages = get().images;
    const finalImages = images ? images : currentImages;
    set((state) => ({
      classes,
      annotations,
      images: finalImages,
      selectedImageId: finalImages.length > 0 ? finalImages[0].id : state.selectedImageId,
      selectedAnnotationId: null
    }));
    get().saveHistory();
  },

  setImageProgress: (imageId, progress) => {
    set((state) => ({
      images: state.images.map(img => img.id === imageId ? { ...img, progress } : img)
    }));
  },

  // Recorded once the canvas has the natural size, so saved annotations carry
  // real pixel dimensions alongside the normalised coordinates.
  setImageDimensions: (imageId, width, height) => {
    const { images } = get();
    const target = images.find(img => img.id === imageId);
    if (!target || (target.width === width && target.height === height)) return;

    set({
      images: images.map(img =>
        img.id === imageId ? { ...img, width, height } : img
      )
    });
  },

  // ============================================================
  // AZURE BACKED LOADING
  // ============================================================

  loadVideoProject: (videoId, stage, images, annotations) => {
    set({
      videoId,
      stage,
      images,
      classes: DEFAULT_CLASSES,
      annotations,
      selectedImageId: images.length > 0 ? images[0].id : null,
      selectedAnnotationId: null,
      canvasSettings: INITIAL_SETTINGS,
      history: [
        {
          images: JSON.parse(JSON.stringify(images)),
          classes: DEFAULT_CLASSES,
          annotations: JSON.parse(JSON.stringify(annotations))
        }
      ],
      historyIndex: 0
    });
  },

  readDraft: (videoId, stage) => {
    if (typeof window === 'undefined') return null;
    try {
      const raw = localStorage.getItem(draftKey(videoId, stage));
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed?.annotations)) return null;
      return parsed.annotations as Annotation[];
    } catch (e) {
      console.error('Failed to read draft:', e);
      return null;
    }
  },

  clearDraft: (videoId, stage) => {
    if (typeof window === 'undefined') return;
    localStorage.removeItem(draftKey(videoId, stage));
  }
}));
