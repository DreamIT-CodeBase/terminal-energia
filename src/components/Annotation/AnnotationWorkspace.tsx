'use client';

import React, {
  useCallback,
  useEffect,
  useRef,
  useState
} from 'react';

import {
  Loader2,
  RefreshCw
} from 'lucide-react';

import { UpperToolbar } from '../Toolbar/UpperToolbar';
import { LeftSidebar } from '../Sidebar/LeftSidebar';
import { RightSidebar } from '../Sidebar/RightSidebar';
import { VideoStage } from '../Video/VideoStage';
import { DynamicCanvas } from '../Canvas/DynamicCanvas';
import { CanvasToolbar } from '../Canvas/CanvasToolbar';

import {
  formatTimestamp,
  useAnnotationStore
} from '../../store/useAnnotationStore';

import { readVideoMetadata } from '../../utils/videoMetadata';

import {
  CapturedFrame,
  CapturedFrameRecord,
  SubmitResult,
  VideoAssets,
  VideoProbe,
  VideoSummary
} from '../../types';


interface AnnotationWorkspaceProps {
  userEmail: string;
  // Sign-in is temporarily off, so the tool is open to anyone with the URL.
  authDisabled?: boolean;
  // Offer every video, not just the ones still to do.
  showAllVideos?: boolean;
}


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


const toFrame = (
  record: CapturedFrameRecord
): CapturedFrame => ({
  name: record.name,
  path: record.path,
  timestampMs: record.timestamp_ms,
  width: record.width,
  height: record.height,
  size: record.size
});


export const AnnotationWorkspace: React.FC<AnnotationWorkspaceProps> = ({
  userEmail,
  authDisabled = false,
  showAllVideos = false
}) => {

  const {
    videoId,
    durationMs,
    frames,
    boards,
    regions,
    selection,
    images,
    selectedImageId,
    loadVideo,
    clearProject,
    setFrames,
    setDurationMs,
    select,
    readDraft,
    clearDraft
  } = useAnnotationStore();


  // ============================================================
  // STATES
  // ============================================================

  const [videos, setVideos] = useState<VideoSummary[]>([]);
  const [assets, setAssets] = useState<VideoAssets | null>(null);
  const [apiResponse, setApiResponse] = useState<unknown>(null);
  const [fps, setFps] = useState<number | null>(null);

  const [isLoadingVideos, setIsLoadingVideos] = useState(true);
  const [isLoadingVideo, setIsLoadingVideo] = useState(false);
  const [loadError, setLoadError] = useState('');

  const [isCapturing, setIsCapturing] = useState(false);
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
  // OPEN ONE VIDEO
  //
  // A video is the whole job: the clip, the frames already
  // captured from it, and whatever was drawn on them but not
  // yet submitted.
  // ============================================================

  const openVideo = useCallback(
    async (
      nextVideoId: string
    ) => {

      setIsLoadingVideo(true);
      setLoadError('');
      setStatusMessage('');
      setStatusIsSuccess(false);
      setFps(null);

      try {

        // What the source container holds for this video: the clip itself,
        // and whether there is an api_response.json worth reading.
        const assetsResponse = await fetch(
          `/api/videos/${encodeURIComponent(nextVideoId)}`,
          { cache: 'no-store' }
        );

        if (!assetsResponse.ok) {
          throw new Error(await readError(assetsResponse));
        }

        const videoAssets: VideoAssets = await assetsResponse.json();

        setAssets(videoAssets);


        // --------------------------------------------------
        // THE CLIP
        //
        // A probe that fails is not fatal: the browser reports
        // the duration once the video element has metadata.
        // --------------------------------------------------

        let probedDurationMs = 0;

        const probeResponse = await fetch(
          `/api/videos/${encodeURIComponent(nextVideoId)}/probe`,
          { cache: 'no-store' }
        );

        if (probeResponse.ok) {
          const probe: VideoProbe = await probeResponse.json();
          probedDurationMs = probe.duration_ms;
          setFps(probe.fps);
        }


        // --------------------------------------------------
        // FRAMES ALREADY CAPTURED
        // --------------------------------------------------

        const framesResponse = await fetch(
          `/api/videos/${encodeURIComponent(nextVideoId)}/frames`,
          { cache: 'no-store' }
        );

        if (!framesResponse.ok) {
          throw new Error(await readError(framesResponse));
        }

        const captured: { frames?: CapturedFrameRecord[] } =
          await framesResponse.json();

        const nextFrames = (captured.frames ?? []).map(toFrame);


        // --------------------------------------------------
        // WHAT WAS DRAWN BUT NOT SUBMITTED
        //
        // The frames live on the server, the boxes do not, so a
        // draft only has to carry the boxes and their prices.
        // --------------------------------------------------

        const draft = readDraft(nextVideoId);

        const known = new Set(nextFrames.map(frame => frame.name));

        const nextBoards = (draft?.boards ?? []).filter(
          board => known.has(board.frameName)
        );

        const boardIds = new Set(nextBoards.map(board => board.id));

        const nextRegions = (draft?.regions ?? []).filter(
          region => boardIds.has(region.boardId)
        );


        loadVideo(
          nextVideoId,
          probedDurationMs,
          nextFrames,
          nextBoards,
          nextRegions
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
    [loadVideo, readDraft]
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
            has_video?: boolean;
          }[];
        } = await response.json();

        const summaries: VideoSummary[] = (data.videos ?? [])
          .map(item => ({
            videoId: item.video_id,
            annotated: item.annotated,
            imageCount: item.image_count ?? 0,
            completed: item.completed ?? false,
            hasVideo: item.has_video ?? false
          }))
          // Frames are captured from the clip, so a video without one is a
          // dead end and never reaches the queue.
          .filter(video => video.hasVideo)
          // The queue is normally work still to do, so anything already
          // filed into a week drops out. While showAllVideos is set every
          // video stays on offer, and a finished one can be labelled again --
          // the dropdown marks those "(saved)".
          .filter(video => showAllVideos || !video.annotated);

        setVideos(summaries);

        if (summaries[0]) {
          await openVideo(summaries[0].videoId);
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
    [clearProject, openVideo, showAllVideos]
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
  // CAPTURE FRAMES
  //
  // The browser sends moments, not pixels. The API decodes each
  // one from the stored clip and parks it in the working area.
  // ============================================================

  const handleCapture = async (
    timestampsMs: number[]
  ) => {

    if (!videoId || timestampsMs.length === 0) return;

    setIsCapturing(true);
    setStatusIsSuccess(false);
    setStatusMessage(
      timestampsMs.length === 1
        ? `Capturing the frame at ${formatTimestamp(timestampsMs[0])}...`
        : `Capturing ${timestampsMs.length} frames...`
    );

    try {

      const response = await fetch(
        `/api/videos/${encodeURIComponent(videoId)}/frames`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ timestampsMs })
        }
      );

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const result: {
        frames: CapturedFrameRecord[];
        captured: CapturedFrameRecord[];
      } = await response.json();

      setFrames(result.frames.map(toFrame));

      // Land on what was just taken, so a single capture goes straight to
      // drawing rather than back to a list.
      const landing = result.captured[0];

      if (landing) {
        select({ kind: 'frame', frameName: landing.name });
      }

      setStatusMessage(
        result.captured.length === 1
          ? `Captured ${formatTimestamp(result.captured[0].timestamp_ms)}.`
          : `Captured ${result.captured.length} frames.`
      );

    } catch (error) {

      console.error('Capture error:', error);

      setStatusMessage(
        error instanceof Error
          ? `Capture failed: ${error.message}`
          : 'Capture failed.'
      );

    } finally {
      setIsCapturing(false);
    }
  };


  // ============================================================
  // DISCARD A FRAME
  // ============================================================

  const handleDeleteFrame = async (
    frameName: string
  ) => {

    if (!videoId) return;

    const frameBoards = boards.filter(
      board => board.frameName === frameName
    );

    if (frameBoards.length > 0) {

      const confirmed = window.confirm(
        `Discard this frame? The ${frameBoards.length} board(s) drawn on it, ` +
        'and their prices, go with it.'
      );

      if (!confirmed) return;
    }

    try {

      const response = await fetch(
        `/api/videos/${encodeURIComponent(videoId)}` +
        `/frames/${encodeURIComponent(frameName)}`,
        { method: 'DELETE' }
      );

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const result: { frames: CapturedFrameRecord[] } = await response.json();

      setFrames(result.frames.map(toFrame));

    } catch (error) {

      console.error('Frame delete error:', error);

      setStatusMessage(
        error instanceof Error
          ? `Could not discard the frame: ${error.message}`
          : 'Could not discard the frame.'
      );
    }
  };


  // ============================================================
  // SUBMIT THE VIDEO
  //
  // One request closes the video out: frames, boards, regions
  // and prices all go together.
  // ============================================================

  const handleSubmit = async () => {

    if (!videoId) return;

    const payload = frames
      .map(frame => ({
        name: frame.name,
        timestampMs: frame.timestampMs,
        width: frame.width,
        height: frame.height,
        boards: boards
          .filter(board => board.frameName === frame.name)
          .map(board => ({
            coordinates: board.coordinates,
            regions: regions
              .filter(region => region.boardId === board.id)
              .map(region => ({
                coordinates: region.coordinates,
                // An empty field means the price could not be read, which is
                // recorded as no price rather than as a zero.
                price:
                  region.price.trim() === ''
                    ? null
                    : Number(region.price)
              }))
          }))
      }))
      // A frame nobody drew on is not part of the dataset.
      .filter(frame => frame.boards.length > 0);

    if (payload.length === 0) {
      setStatusMessage('Draw at least one board before submitting.');
      return;
    }

    const emptyBoards = payload
      .flatMap(frame => frame.boards)
      .filter(board => board.regions.length === 0).length;

    const boardCount = payload.reduce(
      (total, frame) => total + frame.boards.length,
      0
    );

    const confirmed = window.confirm(
      `Submit ${payload.length} frame(s) and ${boardCount} board(s)? ` +
      'This files the video into this week and finishes it.' +
      (
        emptyBoards > 0
          ? `\n\n${emptyBoards} board(s) have no price region and will be ` +
            'filed as background samples.'
          : ''
      )
    );

    if (!confirmed) return;

    setIsSubmitting(true);
    setStatusIsSuccess(false);
    setStatusMessage('Submitting...');

    try {

      const response = await fetch(
        `/api/videos/${encodeURIComponent(videoId)}/submit`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ frames: payload })
        }
      );

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const result: SubmitResult = await response.json();

      // Everything in the draft is now in storage.
      clearDraft(videoId);

      const skippedNote =
        result.skipped.length > 0
          ? ` Skipped: ${result.skipped.join(', ')}.`
          : '';


      // --------------------------------------------------
      // DROP THE VIDEO AND MOVE ON
      // --------------------------------------------------

      const finishedIndex = videos.findIndex(
        video => video.videoId === videoId
      );

      const remaining = videos.filter(
        video => video.videoId !== videoId
      );

      setVideos(remaining);

      // Prefer the next entry down the list (the older video); fall back to
      // the previous one when this was the last.
      const following =
        finishedIndex === -1
          ? remaining[0]
          : remaining[finishedIndex] ?? remaining[finishedIndex - 1];

      const summary =
        `Filed ${result.frames_written} frame(s) and ` +
        `${result.crops_written} board crop(s) with ` +
        `${result.regions_written} price region(s) into ${result.week}.`;

      if (!following) {
        clearProject();
        setStatusIsSuccess(true);
        setStatusMessage(
          `${summary} That was the last video: everything is annotated.` +
          skippedNote
        );
        return;
      }

      await openVideo(following.videoId);

      // Set after the load, which clears the message on its way in.
      setStatusIsSuccess(true);
      setStatusMessage(
        `${summary} Moved on to ${following.videoId}.` + skippedNote
      );

    } catch (error) {

      console.error('Submit error:', error);

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

  const hasVideo = Boolean(assets?.video_path);

  const showingVideo = selection.kind === 'video';


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
        onVideoChange={openVideo}
        onRefresh={loadVideos}
        onSubmit={handleSubmit}
        isSubmitting={isSubmitting}
      />


      <div className="flex flex-1 min-h-0 relative">

        {/* LEFT: video, frames, boards */}

        <LeftSidebar
          hasVideo={hasVideo}
          isCapturing={isCapturing}
          onDeleteFrame={handleDeleteFrame}
        />


        {/* CENTRE: whichever of the three is selected */}

        <div
          ref={containerRef}
          className="flex-1 h-full relative overflow-hidden bg-[#0a0a0c]"
        >

          {
            !isLoadingVideo && !loadError && showingVideo && (
              <VideoStage
                videoId={videoId}
                hasVideo={hasVideo}
                durationMs={durationMs}
                fps={fps}
                isCapturing={isCapturing}
                capturedTimestamps={frames.map(frame => frame.timestampMs)}
                onCapture={handleCapture}
                onDurationKnown={setDurationMs}
              />
            )
          }

          {
            !isLoadingVideo &&
            !loadError &&
            !showingVideo &&
            images.length > 0 &&
            selectedImageId &&
            canvasDimensions.width > 0 &&
            canvasDimensions.height > 0 && (
              <>
                <DynamicCanvas
                  containerWidth={canvasDimensions.width}
                  containerHeight={canvasDimensions.height}
                  metadata={metadata}
                />

                <CanvasToolbar />

                {/* WHAT THIS VIEW IS FOR */}

                <div className="absolute top-4 right-4 z-20 flex items-center gap-2 px-3 py-2 rounded bg-[#141416]/95 border border-[#303036] text-xs">
                  <span className="text-zinc-500">
                    {
                      selection.kind === 'frame'
                        ? 'Draw boards'
                        : 'Draw price regions'
                    }
                  </span>
                  <span className="text-zinc-600">·</span>
                  <span className="font-semibold text-zinc-100">
                    {images[0]?.name}
                  </span>
                </div>
              </>
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
            !isLoadingVideo && !loadError && !showingVideo &&
            images.length === 0 && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 px-8 text-center text-zinc-500">
                <div>
                  {
                    videos.length === 0
                      ? 'No videos with a video.mp4 in the debug directory container.'
                      : 'Nothing selected. Pick a frame or a board on the left.'
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
