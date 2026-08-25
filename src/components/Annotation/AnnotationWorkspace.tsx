'use client';

import React, {
  useCallback,
  useEffect,
  useRef,
  useState
} from 'react';

import {
  ArrowRight,
  CheckCircle2,
  Loader2,
  RefreshCw
} from 'lucide-react';

import { UpperToolbar } from '../Toolbar/UpperToolbar';
import { ImageSidebar } from '../Sidebar/ImageSidebar';
import { ReferencePanel } from '../Sidebar/ReferencePanel';
import { RightSidebar } from '../Sidebar/RightSidebar';
import { VideoPlayer } from '../Video/VideoPlayer';
import { DynamicCanvas } from '../Canvas/DynamicCanvas';
import { CanvasToolbar } from '../Canvas/CanvasToolbar';

import { useAnnotationStore } from '../../store/useAnnotationStore';
import { readVideoMetadata } from '../../utils/videoMetadata';

import {
  Annotation,
  ImageItem,
  StageState,
  VideoAssets,
  VideoSummary,
  WorkflowStage
} from '../../types';


interface AnnotationWorkspaceProps {
  userEmail: string;
  // Sign-in is temporarily off, so the tool is open to anyone with the URL.
  authDisabled?: boolean;
  // Offer every video, not just the ones still to do.
  showAllVideos?: boolean;
}


// The class every box gets, decided by the stage rather than by the user.
const STAGE_CLASS_ID: Record<WorkflowStage, string> = {
  board: 'class-board',
  price_region: 'class-price-region'
};


// ============================================================
// HELPERS
// ============================================================

const readError = async (
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


/**
 * Stable image ids keyed on the blob path, so boxes restored from Azure and
 * drafts kept in the browser both keep pointing at the right image.
 */
const imageIdFor = (
  blobPath: string
): string => `blob:${blobPath}`;


export const AnnotationWorkspace: React.FC<AnnotationWorkspaceProps> = ({
  userEmail,
  authDisabled = false,
  showAllVideos = false
}) => {

  const {
    images,
    annotations,
    videoId,
    stage,
    selectedImageId,
    loadVideoProject,
    readDraft,
    clearDraft,
    clearProject
  } = useAnnotationStore();


  // ============================================================
  // STATES
  // ============================================================

  const [videos, setVideos] = useState<VideoSummary[]>([]);
  const [assets, setAssets] = useState<VideoAssets | null>(null);
  const [apiResponse, setApiResponse] = useState<unknown>(null);

  const [isLoadingVideos, setIsLoadingVideos] = useState(true);
  const [isLoadingVideo, setIsLoadingVideo] = useState(false);
  const [loadError, setLoadError] = useState('');

  const [isSubmitting, setIsSubmitting] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');
  // Finishing a video is worth calling out; everything else reads as neutral.
  const [statusIsSuccess, setStatusIsSuccess] = useState(false);


  // ============================================================
  // CANVAS SIZE
  // ============================================================

  const containerRef = useRef<HTMLDivElement>(null);

  const [canvasDimensions, setCanvasDimensions] = useState({
    width: 0,
    height: 0
  });


  // ============================================================
  // LOAD ONE VIDEO
  //
  // Stage 1 annotates the extracted frames; stage 2 annotates
  // the crops those board boxes produced.
  // ============================================================

  const loadVideo = useCallback(
    async (
      nextVideoId: string
    ) => {

      setIsLoadingVideo(true);
      setLoadError('');
      setStatusMessage('');
      setStatusIsSuccess(false);

      try {

        // Where this video is in the two-pass flow.
        const stageResponse = await fetch(
          `/api/videos/${encodeURIComponent(nextVideoId)}/stage`,
          { cache: 'no-store' }
        );

        if (!stageResponse.ok) {
          throw new Error(await readError(stageResponse));
        }

        const state: StageState = await stageResponse.json();

        const nextStage: WorkflowStage =
          state.stage === 'price_region'
            ? 'price_region'
            : 'board';


        // Frames are fetched either way: stage 1 annotates them, and the
        // video panel needs to know whether a video.mp4 exists.
        const assetsResponse = await fetch(
          `/api/videos/${encodeURIComponent(nextVideoId)}`,
          { cache: 'no-store' }
        );

        if (!assetsResponse.ok) {
          throw new Error(await readError(assetsResponse));
        }

        const videoAssets: VideoAssets = await assetsResponse.json();

        setAssets(videoAssets);


        // Only frames are loaded for annotation; crops and prepped images in
        // the source container are not part of this flow.
        const nextImages: ImageItem[] =
          nextStage === 'price_region'

            ? (state.crops ?? []).map(crop => ({
                id: imageIdFor(crop.path),
                name: crop.name,
                width: 0,
                height: 0,
                url:
                  `/api/videos/${encodeURIComponent(nextVideoId)}` +
                  `/crop/${encodeURIComponent(crop.name)}`,
                progress: 'todo' as const,
                blobPath: crop.path,
                workflowStage: 'price_region' as const
              }))

            : (videoAssets.folders?.frames ?? []).map(file => ({
                id: imageIdFor(file.path),
                name: file.name,
                // Natural size is filled in by the canvas once it loads.
                width: 0,
                height: 0,
                url:
                  `/api/videos/${encodeURIComponent(nextVideoId)}` +
                  `/file/frames/${encodeURIComponent(file.name)}`,
                progress: 'todo' as const,
                blobPath: file.path,
                folder: 'frames' as const,
                workflowStage: 'board' as const
              }));


        // --------------------------------------------------
        // BOXES ALREADY SUBMITTED FOR THIS STAGE
        // --------------------------------------------------

        let nextAnnotations: Annotation[] = [];

        const submitted =
          nextStage === 'board'
            ? state.board_submitted
            : state.price_region_submitted;

        if (submitted) {

          const savedResponse = await fetch(
            `/api/videos/${encodeURIComponent(nextVideoId)}` +
            `/stage/${nextStage}/annotations`,
            { cache: 'no-store' }
          );

          if (savedResponse.ok) {

            const saved: {
              images?: {
                path: string;
                width?: number;
                height?: number;
                annotations: { classId: string; coordinates: number[] }[];
              }[];
            } = await savedResponse.json();

            const known = new Set(nextImages.map(image => image.id));

            saved.images?.forEach(savedImage => {

              const imageId = imageIdFor(savedImage.path);

              if (!known.has(imageId)) return;

              savedImage.annotations.forEach((record, index) => {
                nextAnnotations.push({
                  id: `${imageId}-${index}`,
                  imageId,
                  classId: record.classId,
                  type: 'rect',
                  coordinates: record.coordinates
                });
              });

              const target = nextImages.find(image => image.id === imageId);

              if (target && savedImage.annotations.length > 0) {
                target.progress = 'done';
                target.width = savedImage.width || 0;
                target.height = savedImage.height || 0;
              }
            });

          } else {
            // The server says this stage was submitted, so a failure here
            // means previously drawn boxes are silently missing from the
            // canvas. Worth surfacing rather than starting from blank.
            setLoadError(
              'Boxes for this stage were saved but could not be loaded: ' +
              await readError(savedResponse) +
              ' Redrawing now would overwrite them on submit.'
            );

            return;
          }
        }


        // --------------------------------------------------
        // LOCAL DRAFT WINS OVER WHAT IS IN STORAGE
        // --------------------------------------------------

        const draft = readDraft(nextVideoId, nextStage);

        if (draft && draft.length > 0) {

          const known = new Set(nextImages.map(image => image.id));

          const usable = draft.filter(
            annotation => known.has(annotation.imageId)
          );

          if (usable.length > 0) {
            nextAnnotations = usable;

            nextImages.forEach(image => {
              image.progress = usable.some(
                annotation => annotation.imageId === image.id
              )
                ? 'in-progress'
                : 'todo';
            });
          }
        }


        loadVideoProject(
          nextVideoId,
          nextStage,
          nextImages,
          nextAnnotations
        );


        // --------------------------------------------------
        // api_response.json
        // --------------------------------------------------

        setApiResponse(null);

        if (videoAssets.has_api_response) {

          const apiResponseResult = await fetch(
            `/api/videos/${encodeURIComponent(nextVideoId)}/api-response`,
            { cache: 'no-store' }
          );

          if (apiResponseResult.ok) {
            setApiResponse(await apiResponseResult.json());
          }
        }

      } catch (error) {

        console.error('Video loading error:', error);

        setLoadError(
          error instanceof Error
            ? error.message
            : 'Unable to load this video from Azure.'
        );

      } finally {
        setIsLoadingVideo(false);
      }
    },
    [loadVideoProject, readDraft]
  );


  // ============================================================
  // LOAD THE VIDEO LIST
  // ============================================================

  const loadVideos = useCallback(
    async () => {

      setIsLoadingVideos(true);
      setLoadError('');

      try {

        const response = await fetch('/api/videos', { cache: 'no-store' });

        if (!response.ok) {
          throw new Error(await readError(response));
        }

        const data: {
          videos: {
            video_id: string;
            annotated: boolean;
            image_count?: number;
            completed?: boolean;
          }[];
        } = await response.json();

        const summaries: VideoSummary[] = (data.videos ?? [])
          .map(item => ({
            videoId: item.video_id,
            annotated: item.annotated,
            imageCount: item.image_count ?? 0,
            completed: item.completed ?? false
          }))
          // The queue is normally work still to do, so anything already
          // written to the annotated container drops out. While
          // showAllVideos is set every video stays on offer, and an annotated
          // one can be labelled again -- the dropdown marks those "(saved)".
          .filter(video => showAllVideos || !video.annotated);

        setVideos(summaries);

        // The API returns videos newest first. Plenty hold only JSON, so open
        // on the most recent one that actually has frames.
        const opening =
          summaries.find(video => video.imageCount > 0) ?? summaries[0];

        if (opening) {
          await loadVideo(opening.videoId);
        } else {
          clearProject();
        }

      } catch (error) {

        console.error('Video list error:', error);

        setLoadError(
          error instanceof Error
            ? error.message
            : 'Unable to reach the system API.'
        );

      } finally {
        setIsLoadingVideos(false);
      }
    },
    [clearProject, loadVideo]
  );


  useEffect(
    () => {
      // Fetching the video list on mount is the whole point of this effect,
      // and the loading flags it sets are what the first render waits on.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      loadVideos();
    },
    // Run once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  );


  // ============================================================
  // SUBMIT THE CURRENT STAGE
  //
  // Stage 1 submits the board boxes; the server crops every
  // frame and the workspace reloads onto those crops. Stage 2
  // submits the price regions and completes the video.
  // ============================================================

  const handleSubmitStage = async () => {

    if (!videoId) return;

    const payload = images
      .filter(image =>
        annotations.some(annotation => annotation.imageId === image.id)
      )
      .map(image => ({
        name: image.name,
        path: image.blobPath ?? '',
        width: image.width,
        height: image.height,
        annotations: annotations
          .filter(annotation => annotation.imageId === image.id)
          .map(annotation => ({
            classId: STAGE_CLASS_ID[stage],
            type: 'rect' as const,
            coordinates: annotation.coordinates
          }))
      }));

    if (payload.length === 0) {
      setStatusMessage(
        stage === 'board'
          ? 'Draw at least one board box before submitting.'
          : 'Draw at least one price region before submitting.'
      );
      return;
    }

    const confirmed = window.confirm(
      stage === 'board'
        ? `Submit boards for ${payload.length} frame(s)? The frames will be ` +
          'cropped to their board boxes, and those crops become the images ' +
          'for the price region pass.'
        : `Submit price regions for ${payload.length} crop(s)? ` +
          'This completes the video.'
    );

    if (!confirmed) return;

    setIsSubmitting(true);
    setStatusIsSuccess(false);
    setStatusMessage(
      stage === 'board'
        ? 'Submitting boards and cropping frames...'
        : 'Submitting price regions...'
    );

    try {

      const response = await fetch(
        `/api/videos/${encodeURIComponent(videoId)}` +
        `/stage/${stage === 'board' ? 'board' : 'price-region'}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ images: payload })
        }
      );

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const result: {
        images_written: number;
        crops: { name: string }[];
        skipped: string[];
      } = await response.json();

      // The draft has been superseded by what is now in storage.
      clearDraft(videoId, stage);

      const skippedNote =
        result.skipped.length > 0
          ? ` Skipped: ${result.skipped.join(', ')}.`
          : '';


      if (stage === 'board') {

        setVideos(current =>
          current.map(video =>
            video.videoId === videoId
              ? { ...video, annotated: true }
              : video
          )
        );

        setStatusMessage(
          `Saved ${result.images_written} frame(s) and produced ` +
          `${result.crops.length} crop(s). Now label the price regions.` +
          skippedNote
        );

        // Reload so the workspace picks up the stage the server now reports.
        await loadVideo(videoId);

        return;
      }


      // ----------------------------------------------------------
      // BOTH PASSES DONE: DROP THE VIDEO AND MOVE ON
      //
      // The same step the Next button takes, so finishing a video
      // continues down the queue without a manual selection.
      // ----------------------------------------------------------

      const finishedIndex = videos.findIndex(
        video => video.videoId === videoId
      );

      const remaining = videos.filter(
        video => video.videoId !== videoId
      );

      setVideos(remaining);

      // Prefer the next entry down the list (the older video, which is where
      // Next goes); fall back to the previous one when this was the last.
      const following =
        finishedIndex === -1
          ? remaining[0]
          : remaining[finishedIndex] ?? remaining[finishedIndex - 1];

      if (!following) {
        clearProject();
        setStatusIsSuccess(true);
        setStatusMessage(
          `Saved ${result.images_written} crop(s). ` +
          'That was the last video: everything is annotated.' +
          skippedNote
        );
        return;
      }

      const completionMessage =
        `Saved ${result.images_written} crop(s). ${videoId} is complete, ` +
        `moved on to ${following.videoId}.` +
        skippedNote;

      await loadVideo(following.videoId);

      // Set after the load, which clears the message on its way in.
      setStatusIsSuccess(true);
      setStatusMessage(completionMessage);

    } catch (error) {

      console.error('Stage submit error:', error);

      setStatusMessage(
        error instanceof Error
          ? `Submit failed: ${error.message}`
          : 'Submit failed.'
      );

    } finally {
      setIsSubmitting(false);
    }
  };


  // ============================================================
  // CANVAS RESIZE
  // ============================================================

  useEffect(
    () => {

      if (!containerRef.current) return;

      const initialRect = containerRef.current.getBoundingClientRect();

      setCanvasDimensions({
        width: Math.floor(initialRect.width),
        height: Math.floor(initialRect.height)
      });

      const observer = new ResizeObserver(entries => {
        for (const entry of entries) {
          setCanvasDimensions({
            width: Math.floor(entry.contentRect.width),
            height: Math.floor(entry.contentRect.height)
          });
        }
      });

      observer.observe(containerRef.current);

      return () => observer.disconnect();
    },
    [isLoadingVideos]
  );


  // ============================================================
  // FIRST LOAD
  // ============================================================

  if (isLoadingVideos) {

    return (
      <div className="w-screen h-screen flex flex-col items-center justify-center gap-3 bg-[#0d0d10] text-zinc-300">
        <Loader2 className="w-8 h-8 animate-spin text-blue-400" />
        <div>Loading videos from Azure...</div>
      </div>
    );
  }


  // Capture time and position come from the video's api_response.json and
  // are shown alongside the zoom and frame size.
  const metadata = readVideoMetadata(apiResponse);


  const annotatedCount = images.filter(image =>
    annotations.some(annotation => annotation.imageId === image.id)
  ).length;

  // ============================================================
  // WORKSPACE
  // ============================================================

  return (

    <div className="flex flex-col h-screen overflow-hidden bg-[#0d0d10] text-zinc-200">

      <UpperToolbar
        userEmail={userEmail}
        authDisabled={authDisabled}
        showAllVideos={showAllVideos}
        videos={videos}
        videoId={videoId}
        onVideoChange={loadVideo}
        onRefresh={loadVideos}
      />


      <div className="flex flex-1 min-h-0 relative">

        {/* LEFT COLUMN: frames on top, the source clip underneath */}

        <div className="w-64 flex flex-col h-full border-r border-[#232326] shrink-0 relative">

          {/* Slides out over the canvas from this pane's right edge */}
          <ReferencePanel
            videoId={videoId}
            crops={assets?.folders?.crops ?? []}
            prepped={assets?.folders?.prepped ?? []}
          />


          <div className="flex-1 min-h-0">
            <ImageSidebar
              videos={videos}
              videoId={videoId}
              onVideoChange={loadVideo}
            />
          </div>

          <div className="h-56 shrink-0 border-t border-[#232326] bg-[#0a0a0c] flex flex-col">
            <div className="px-3 h-8 flex items-center shrink-0 text-[10px] uppercase font-bold tracking-wider text-zinc-500 bg-[#19191c] border-b border-[#232326]/50">
              Source video
            </div>
            <div className="flex-1 min-h-0">
              <VideoPlayer
                videoId={videoId}
                hasVideo={Boolean(assets?.video_path)}
              />
            </div>
          </div>

        </div>


        {/* CENTER: what the boxes are drawn on */}

        <div
          ref={containerRef}
          className="flex-1 h-full relative overflow-hidden bg-[#0a0a0c]"
        >

          {
            !isLoadingVideo &&
            images.length > 0 &&
            selectedImageId &&
            canvasDimensions.width > 0 &&
            canvasDimensions.height > 0 && (
              <DynamicCanvas
                containerWidth={canvasDimensions.width}
                containerHeight={canvasDimensions.height}
                metadata={metadata}
              />
            )
          }


          {/* STAGE BANNER AND SUBMIT */}

          {
            !isLoadingVideo && !loadError && images.length > 0 && (

              <div className="absolute top-4 right-4 z-20 flex items-center gap-2">

                <div className="flex items-center gap-2 px-3 py-2 rounded bg-[#141416]/95 border border-[#303036] text-xs">
                  <span className="text-zinc-500">Stage</span>
                  <span className="font-semibold text-zinc-100">
                    {stage === 'board' ? '1 · Boards' : '2 · Price regions'}
                  </span>
                  <span className="text-zinc-600">·</span>
                  <span className="text-zinc-400 font-mono">
                    {annotatedCount}/{images.length}
                  </span>
                </div>

                <button
                  onClick={handleSubmitStage}
                  disabled={isSubmitting || annotations.length === 0}
                  title={
                    stage === 'board'
                      ? 'Save the board boxes and crop the frames'
                      : 'Save the price region boxes and finish this video'
                  }
                  className="flex items-center gap-2 px-4 py-2 rounded bg-blue-600 hover:bg-blue-500 disabled:opacity-40 text-sm font-semibold text-white transition-colors"
                >
                  {
                    isSubmitting
                      ? <Loader2 className="w-4 h-4 animate-spin" />
                      : stage === 'board'
                        ? <ArrowRight className="w-4 h-4" />
                        : <CheckCircle2 className="w-4 h-4" />
                  }
                  {
                    stage === 'board'
                      ? 'Submit boards'
                      : 'Submit price regions'
                  }
                </button>

              </div>
            )
          }


          {/* LOADING */}

          {
            isLoadingVideo && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-zinc-400">
                <Loader2 className="w-6 h-6 animate-spin text-blue-400" />
                <div>Loading {videoId}...</div>
              </div>
            )
          }


          {/* ERROR */}

          {
            !isLoadingVideo && loadError && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 px-8 text-center text-red-400">
                <div>{loadError}</div>
                <button
                  onClick={loadVideos}
                  className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded"
                >
                  <RefreshCw className="w-4 h-4" />
                  Retry
                </button>
              </div>
            )
          }


          {/* EMPTY */}

          {
            !isLoadingVideo && !loadError && images.length === 0 && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 px-8 text-center text-zinc-500">
                <div>
                  {
                    videos.length === 0
                      ? 'No videos found in the debug directory container.'
                      : stage === 'price_region'
                        ? `No crops were produced for ${videoId}.`
                        : `No frames in ${videoId}/frames/.`
                  }
                </div>
                <button
                  onClick={loadVideos}
                  className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded"
                >
                  <RefreshCw className="w-4 h-4" />
                  Refresh
                </button>
              </div>
            )
          }


          {
            !isLoadingVideo && images.length > 0 && selectedImageId && (
              <CanvasToolbar />
            )
          }


          {/* STATUS */}

          {
            statusMessage && (
              <div className={`absolute bottom-4 left-1/2 -translate-x-1/2 z-30 max-w-xl rounded-lg border px-4 py-3 text-xs shadow-xl ${
                statusIsSuccess
                  ? 'border-green-500/40 bg-green-500/10 text-green-300'
                  : 'border-[#303036] bg-[#1c1c20] text-zinc-200'
              }`}>
                {statusMessage}
              </div>
            )
          }

        </div>


        <RightSidebar apiResponse={apiResponse} />

      </div>

    </div>
  );
};
