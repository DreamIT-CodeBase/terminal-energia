'use client';

import React from 'react';

import { useAnnotationStore } from '../../store/useAnnotationStore';
import { signOutAction } from '../../app/actions';
import { VideoSummary } from '../../types';

import {
  Undo2,
  Redo2,
  ZoomIn,
  ZoomOut,
  Maximize,
  RefreshCw,
  Image as ImageIcon,
  CheckCircle,
  CheckCircle2,
  Loader2,
  Tag,
  LogOut,
  ShieldAlert,
  Video
} from 'lucide-react';


interface UpperToolbarProps {
  userEmail: string;
  authDisabled?: boolean;
  // Annotated videos are on offer too, so they can be redone.
  showAllVideos?: boolean;
  videos: VideoSummary[];
  videoId: string | null;
  onVideoChange: (videoId: string) => void;
  onRefresh: () => void;
  // Submitting closes out the whole video, so it lives up here rather than on
  // any one of the three sections.
  onSubmit: () => void;
  isSubmitting: boolean;
}


export const UpperToolbar: React.FC<UpperToolbarProps> = ({
  userEmail,
  authDisabled = false,
  showAllVideos = false,
  videos,
  videoId,
  onVideoChange,
  onRefresh,
  onSubmit,
  isSubmitting
}) => {

  const {
    history,
    historyIndex,
    canvasSettings,
    frames,
    boards,
    regions,
    undo,
    redo,
    updateCanvasSettings
  } = useAnnotationStore();


  // ============================================================
  // ZOOM
  // ============================================================

  const handleZoomIn = () => {

    updateCanvasSettings({
      zoom: Math.min(
        30,
        canvasSettings.zoom * 1.2
      )
    });
  };


  const handleZoomOut = () => {

    updateCanvasSettings({
      zoom: Math.max(
        0.05,
        canvasSettings.zoom / 1.2
      )
    });
  };


  const handleFitImage = () => {

    updateCanvasSettings({
      zoom: 1,
      panX: 0,
      panY: 0
    });
  };


  // ============================================================
  // COUNTS
  // ============================================================

  const pricedCount = regions.filter(
    region => region.price.trim() !== ''
  ).length;

  // Nothing can be filed without at least one board on a frame.
  const canSubmit = boards.length > 0;


  // ============================================================
  // UI
  // ============================================================

  return (

    <header
      className="
        flex
        items-center
        justify-between
        h-14
        px-4
        bg-[#141416]
        border-b
        border-[#232326]
        select-none
        text-zinc-300
        font-sans
      "
    >

      {/* ===================================================== */}
      {/* LEFT: PROJECT AND VIDEO */}
      {/* ===================================================== */}

      <div
        className="
          flex
          items-center
          gap-3
        "
      >

        <div
          className="
            flex
            items-center
            gap-2
          "
        >

          <div
            className="
              flex
              items-center
              justify-center
              w-7
              h-7
              rounded
              bg-[#00bcd4]
              font-bold
              text-black
              text-xs
            "
          >
            FP
          </div>

          <span
            className="
              text-xs
              font-black
              tracking-widest
              text-white
              uppercase
              hidden
              lg:inline
            "
          >
            Fuel Price Labelling
          </span>

        </div>


        {/* VIDEO SELECTOR */}

        <div
          className="
            flex
            items-center
            gap-2
            border-l
            border-zinc-800
            pl-3
          "
        >

          <Video
            className="
              w-3.5
              h-3.5
              text-zinc-500
            "
          />

          <select
            value={videoId ?? ''}
            onChange={
              event => onVideoChange(event.target.value)
            }
            disabled={videos.length === 0}
            title="Video folder in the debug directory container"
            className="
              max-w-[220px]
              bg-[#0d0d10]
              border
              border-[#232326]
              text-xs
              text-zinc-200
              rounded
              px-2
              py-1.5
              outline-none
              focus:border-blue-500
              disabled:opacity-40
            "
          >

            {
              videos.length === 0 && (
                <option value="">
                  No videos found
                </option>
              )
            }

            {
              videos.map(video => (
                <option
                  key={video.videoId}
                  value={video.videoId}
                >
                  {video.videoId}
                  {video.annotated ? '  (saved)' : ''}
                </option>
              ))
            }

          </select>


          {
            showAllVideos && (
              <span
                title={
                  'Showing every video, including ones already annotated. ' +
                  'Those are marked "(saved)" in the list, and labelling one ' +
                  'again overwrites what was saved for it.'
                }
                className="
                  shrink-0
                  px-2
                  py-1
                  rounded
                  border
                  border-amber-500/40
                  bg-amber-500/10
                  text-[10px]
                  font-semibold
                  uppercase
                  tracking-wider
                  text-amber-300
                  cursor-help
                "
              >
                All
              </span>
            )
          }


          <button
            onClick={onRefresh}
            title="Reload videos from Azure"
            className="
              p-1.5
              rounded
              hover:bg-[#202024]
              text-zinc-400
              hover:text-zinc-200
            "
          >
            <RefreshCw
              className="
                w-3.5
                h-3.5
              "
            />
          </button>

        </div>


        <div
          className="
            hidden
            xl:flex
            items-center
            gap-2.5
            ml-2
            border-l
            border-zinc-800
            pl-4
            text-xs
            text-zinc-400
          "
        >

          <span
            className="
              flex
              items-center
              gap-1
            "
          >
            <ImageIcon
              className="
                w-3.5
                h-3.5
              "
            />

            {frames.length} frames
          </span>


          <span
            className="
              flex
              items-center
              gap-1
            "
          >
            <Tag
              className="
                w-3.5
                h-3.5
              "
            />

            {boards.length} boards
          </span>


          <span
            className="
              flex
              items-center
              gap-1
            "
          >
            <CheckCircle
              className="
                w-3.5
                h-3.5
                text-green-500
              "
            />

            {pricedCount}/{regions.length} priced
          </span>

        </div>

      </div>


      {/* ===================================================== */}
      {/* MIDDLE: CANVAS CONTROLS */}
      {/* ===================================================== */}

      <div
        className="
          flex
          items-center
          gap-1
        "
      >

        <button
          onClick={undo}
          disabled={historyIndex === 0}
          title="Undo"
          className="
            p-2
            rounded
            hover:bg-[#202024]
            disabled:opacity-40
          "
        >
          <Undo2
            className="
              w-4.5
              h-4.5
            "
          />
        </button>


        <button
          onClick={redo}
          disabled={historyIndex >= history.length - 1}
          title="Redo"
          className="
            p-2
            rounded
            hover:bg-[#202024]
            disabled:opacity-40
          "
        >
          <Redo2
            className="
              w-4.5
              h-4.5
            "
          />
        </button>


        <div
          className="
            w-px
            h-5
            bg-[#232326]
            mx-2
          "
        />


        <button
          onClick={handleZoomOut}
          title="Zoom Out"
          className="
            p-2
            rounded
            hover:bg-[#202024]
          "
        >
          <ZoomOut
            className="
              w-4.5
              h-4.5
            "
          />
        </button>


        <span
          className="
            text-xs
            font-mono
            w-12
            text-center
          "
        >
          {Math.round(canvasSettings.zoom * 100)}%
        </span>


        <button
          onClick={handleZoomIn}
          title="Zoom In"
          className="
            p-2
            rounded
            hover:bg-[#202024]
          "
        >
          <ZoomIn
            className="
              w-4.5
              h-4.5
            "
          />
        </button>


        <button
          onClick={handleFitImage}
          title="Fit Image"
          className="
            p-2
            rounded
            hover:bg-[#202024]
          "
        >
          <Maximize
            className="
              w-4.5
              h-4.5
            "
          />
        </button>



      </div>


      {/* ===================================================== */}
      {/* RIGHT: SUBMIT, ACCOUNT */}
      {/* ===================================================== */}

      <div
        className="
          flex
          items-center
          gap-2
        "
      >

        {/* SUBMIT THE WHOLE VIDEO */}

        <button
          onClick={onSubmit}
          disabled={isSubmitting || !canSubmit}
          title={
            canSubmit
              ? 'File this video into this week and move on'
              : 'Draw at least one board before submitting'
          }
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
            isSubmitting
              ? (
                <Loader2
                  className="
                    w-4
                    h-4
                    animate-spin
                  "
                />
              )
              : (
                <CheckCircle2
                  className="
                    w-4
                    h-4
                  "
                />
              )
          }
          Submit video
        </button>


        {/* ACCOUNT */}

        <div
          className="
            flex
            items-center
            gap-2
            border-l
            border-zinc-800
            pl-2
          "
        >

          {
            authDisabled
              ? (
                // Deliberately loud: while this shows, anyone with the URL can
                // annotate, and saved work is attributed to a placeholder.
                <span
                  title="Microsoft sign-in is turned off for testing. Anyone with this link can annotate."
                  className="
                    flex
                    items-center
                    gap-1.5
                    px-2.5
                    py-1
                    rounded
                    border
                    border-amber-500/40
                    bg-amber-500/10
                    text-[11px]
                    font-semibold
                    text-amber-300
                  "
                >
                  <ShieldAlert
                    className="
                      w-3.5
                      h-3.5
                    "
                  />
                  Sign-in off
                </span>
              )
              : (
                <>
                  <span
                    className="
                      hidden
                      xl:inline
                      text-[11px]
                      text-zinc-500
                      max-w-[180px]
                      truncate
                    "
                    title={userEmail}
                  >
                    {userEmail}
                  </span>

                  <form action={signOutAction}>
                    <button
                      type="submit"
                      title="Sign out"
                      className="
                        p-2
                        rounded
                        hover:bg-[#202024]
                        text-zinc-400
                        hover:text-zinc-200
                      "
                    >
                      <LogOut
                        className="
                          w-4
                          h-4
                        "
                      />
                    </button>
                  </form>
                </>
              )
          }

        </div>

      </div>

    </header>
  );
};
