'use client';

import React, {
  useCallback,
  useEffect,
  useRef,
  useState
} from 'react';

import {
  Camera,
  ChevronLeft,
  ChevronRight,
  Film,
  Loader2,
  Sparkles
} from 'lucide-react';

import { formatTimestamp } from '../../store/useAnnotationStore';


interface VideoStageProps {
  videoId: string | null;
  hasVideo: boolean;
  // Reported by the API before the clip is loaded, so the spread control can
  // be offered immediately.
  durationMs: number;
  fps: number | null;
  isCapturing: boolean;
  // Moments already captured, marked on the scrub bar.
  capturedTimestamps: number[];
  onCapture: (timestampsMs: number[]) => void;
  onDurationKnown: (durationMs: number) => void;
}


// How many frames the spread button offers to take across the clip. Enough
// to cover a drive past a station without burying the labeller in review.
const SPREAD_COUNT = 8;


/**
 * The source clip, and the controls that turn moments in it into frames.
 *
 * Frames are not grabbed off this element: the timestamp is sent to the API,
 * which decodes the exact frame from the stored video. What plays here is
 * only how the labeller finds the moment worth keeping.
 */
export const VideoStage: React.FC<VideoStageProps> = ({
  videoId,
  hasVideo,
  durationMs,
  fps,
  isCapturing,
  capturedTimestamps,
  onCapture,
  onDurationKnown
}) => {

  const videoRef = useRef<HTMLVideoElement>(null);

  const [currentMs, setCurrentMs] = useState(0);
  const [loadedDurationMs, setLoadedDurationMs] = useState(0);


  // A clip the API could not probe still reports its length once the browser
  // has the metadata, so the spread control works either way.
  const effectiveDurationMs = durationMs || loadedDurationMs;

  // Without a frame rate there is no meaningful step, so a tenth of a second
  // stands in: fine enough to land on a readable frame by eye.
  const stepMs = fps && fps > 0 ? 1000 / fps : 100;


  const seekBy = useCallback(
    (deltaMs: number) => {

      const element = videoRef.current;

      if (!element) return;

      element.pause();

      const next = Math.max(
        0,
        Math.min(
          element.currentTime * 1000 + deltaMs,
          Math.max(0, effectiveDurationMs - 1)
        )
      );

      element.currentTime = next / 1000;
    },
    [effectiveDurationMs]
  );


  // ============================================================
  // KEYBOARD
  //
  // Arrow keys step frames and C captures, so the labeller can
  // work the clip without leaving the video.
  // ============================================================

  useEffect(
    () => {

      const handleKeyDown = (event: KeyboardEvent) => {

        const active = document.activeElement;

        if (
          active &&
          (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')
        ) {
          return;
        }

        if (event.key === 'ArrowLeft') {
          event.preventDefault();
          seekBy(-stepMs);

        } else if (event.key === 'ArrowRight') {
          event.preventDefault();
          seekBy(stepMs);

        } else if (event.key === 'c' || event.key === 'C') {
          event.preventDefault();
          onCapture([Math.round(currentMs)]);
        }
      };

      window.addEventListener('keydown', handleKeyDown);

      return () => window.removeEventListener('keydown', handleKeyDown);
    },
    [currentMs, onCapture, seekBy, stepMs]
  );


  if (!videoId || !hasVideo) {

    return (
      <div className="flex flex-col items-center justify-center gap-3 h-full text-zinc-600">
        <Film className="w-8 h-8" />
        <span className="text-sm">No video for this clip</span>
      </div>
    );
  }


  // Evenly spaced across the clip, avoiding the very first and last frames
  // where a dashcam is usually still settling.
  const spreadTimestamps = (): number[] => {

    if (effectiveDurationMs <= 0) return [];

    return Array.from(
      { length: SPREAD_COUNT },
      (_, index) =>
        Math.round(
          (effectiveDurationMs * (index + 1)) / (SPREAD_COUNT + 1)
        )
    );
  };


  return (

    <div className="flex flex-col h-full">

      {/* THE CLIP */}

      <div className="relative flex-1 min-h-0 bg-black">

        <video
          ref={videoRef}
          // Remount on video change so the element picks up the new source.
          key={videoId}
          controls
          preload="metadata"
          onTimeUpdate={event =>
            setCurrentMs(event.currentTarget.currentTime * 1000)
          }
          onSeeked={event =>
            setCurrentMs(event.currentTarget.currentTime * 1000)
          }
          onLoadedMetadata={event => {

            const loaded = Math.round(
              event.currentTarget.duration * 1000
            );

            if (Number.isFinite(loaded) && loaded > 0) {
              setLoadedDurationMs(loaded);
              onDurationKnown(loaded);
            }
          }}
          className="w-full h-full object-contain"
        >
          <source
            src={`/api/videos/${encodeURIComponent(videoId)}/file/video.mp4`}
            type="video/mp4"
          />
          Your browser cannot play this video.
        </video>


        {/* CAPTURE, TOP RIGHT OF THE VIDEO */}

        <div className="absolute top-4 right-4 z-10 flex items-center gap-2">

          <button
            onClick={() => onCapture(spreadTimestamps())}
            disabled={isCapturing || effectiveDurationMs <= 0}
            title={
              `Capture ${SPREAD_COUNT} frames spread evenly across the clip, ` +
              'as a starting set to prune'
            }
            className="
              flex
              items-center
              gap-2
              px-3
              py-2
              rounded
              bg-[#141416]/95
              border
              border-[#303036]
              hover:border-zinc-600
              disabled:opacity-40
              text-xs
              font-semibold
              text-zinc-200
              transition-colors
            "
          >
            <Sparkles className="w-3.5 h-3.5" />
            Spread {SPREAD_COUNT}
          </button>

          <button
            onClick={() => onCapture([Math.round(currentMs)])}
            disabled={isCapturing}
            title="Capture the frame shown now [C]"
            className="
              flex
              items-center
              gap-2
              px-4
              py-2
              rounded
              bg-blue-600
              hover:bg-blue-500
              disabled:opacity-40
              text-sm
              font-semibold
              text-white
              transition-colors
            "
          >
            {
              isCapturing
                ? <Loader2 className="w-4 h-4 animate-spin" />
                : <Camera className="w-4 h-4" />
            }
            Capture frame
          </button>

        </div>

      </div>


      {/* FRAME STEPPING AND WHAT IS ALREADY TAKEN */}

      <div className="shrink-0 border-t border-[#232326] bg-[#141416] px-4 py-3">

        <div className="flex items-center gap-3">

          <button
            onClick={() => seekBy(-stepMs)}
            title="Previous frame [Left]"
            className="flex items-center justify-center w-8 h-8 rounded hover:bg-[#202024] text-zinc-400 hover:text-white transition-colors"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>

          <button
            onClick={() => seekBy(stepMs)}
            title="Next frame [Right]"
            className="flex items-center justify-center w-8 h-8 rounded hover:bg-[#202024] text-zinc-400 hover:text-white transition-colors"
          >
            <ChevronRight className="w-4 h-4" />
          </button>

          <div className="font-mono text-xs text-zinc-300">
            {formatTimestamp(Math.round(currentMs))}
            <span className="text-zinc-600">
              {' / '}
              {formatTimestamp(effectiveDurationMs)}
            </span>
          </div>

          <div className="ml-auto text-[11px] text-zinc-500">
            {capturedTimestamps.length} captured
            {
              fps
                ? ` · ${fps.toFixed(1)} fps`
                : ''
            }
          </div>

        </div>


        {/* WHERE THE CAPTURES SIT ALONG THE CLIP */}

        <div className="relative mt-3 h-1.5 rounded bg-[#232326]">

          {
            effectiveDurationMs > 0 &&
            capturedTimestamps.map(timestamp => (
              <span
                key={timestamp}
                title={formatTimestamp(timestamp)}
                style={{
                  left: `${(timestamp / effectiveDurationMs) * 100}%`
                }}
                className="absolute -top-1 w-0.5 h-3.5 -translate-x-1/2 rounded bg-blue-500"
              />
            ))
          }

          {
            effectiveDurationMs > 0 && (
              <span
                style={{
                  left: `${(currentMs / effectiveDurationMs) * 100}%`
                }}
                className="absolute -top-1.5 w-px h-4.5 -translate-x-1/2 bg-zinc-300"
              />
            )
          }

        </div>

      </div>

    </div>
  );
};
