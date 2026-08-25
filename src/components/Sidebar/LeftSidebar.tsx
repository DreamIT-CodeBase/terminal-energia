'use client';

import React, { useState } from 'react';

import {
  Camera,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Circle,
  Film,
  Images,
  Loader2,
  ScanLine,
  Trash2
} from 'lucide-react';

import {
  boardLabel,
  formatTimestamp,
  frameUrl,
  useAnnotationStore
} from '../../store/useAnnotationStore';


interface LeftSidebarProps {
  hasVideo: boolean;
  isCapturing: boolean;
  onDeleteFrame: (frameName: string) => void;
}


// ============================================================
// SECTION SHELL
// ============================================================

const Section: React.FC<{
  title: string;
  count?: number;
  icon: typeof Film;
  open: boolean;
  onToggle: () => void;
  // A section with a list grows; the video section is a fixed row.
  grows?: boolean;
  children: React.ReactNode;
}> = ({ title, count, icon: Icon, open, onToggle, grows, children }) => (

  <div
    className={`flex flex-col min-h-0 border-b border-[#232326] ${
      open && grows ? 'flex-1' : 'shrink-0'
    }`}
  >

    <button
      onClick={onToggle}
      className="flex items-center justify-between px-3 h-10 shrink-0 bg-[#19191c] hover:bg-[#202024] transition-colors border-b border-[#232326]/50"
    >
      <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-zinc-400">
        <Icon className="w-3.5 h-3.5 text-blue-500" />
        <span>{title}</span>
        {
          count !== undefined && (
            <span className="font-mono text-[10px] text-zinc-600">
              {count}
            </span>
          )
        }
      </div>

      {
        open
          ? <ChevronUp className="w-4 h-4 text-zinc-500" />
          : <ChevronDown className="w-4 h-4 text-zinc-500" />
      }
    </button>

    {open && children}

  </div>
);


/**
 * The three things a video is made of, in the order they are worked through:
 * the clip, the frames taken out of it, and the boards found on those frames.
 *
 * This is navigation only. Clicking an entry decides what the centre of the
 * workspace shows; nothing here draws or edits a box.
 */
export const LeftSidebar: React.FC<LeftSidebarProps> = ({
  hasVideo,
  isCapturing,
  onDeleteFrame
}) => {

  const {
    videoId,
    frames,
    boards,
    regions,
    selection,
    select
  } = useAnnotationStore();

  const [videoOpen, setVideoOpen] = useState(true);
  const [framesOpen, setFramesOpen] = useState(true);
  const [boardsOpen, setBoardsOpen] = useState(true);


  return (

    <aside className="w-64 flex flex-col h-full bg-[#141416] border-r border-[#232326] select-none text-zinc-300">


      {/* ================================================= */}
      {/* VIDEO */}
      {/* ================================================= */}

      <Section
        title="Video"
        icon={Film}
        open={videoOpen}
        onToggle={() => setVideoOpen(!videoOpen)}
      >
        <div className="p-2">
          <button
            onClick={() => select({ kind: 'video' })}
            disabled={!videoId}
            className={`w-full flex items-center gap-2.5 p-2 rounded-lg border text-left transition-all ${
              selection.kind === 'video'
                ? 'bg-blue-600/10 border-blue-500 text-white'
                : 'border-transparent hover:bg-[#1a1a1e] hover:text-zinc-100'
            } disabled:opacity-40`}
          >
            <div className="flex items-center justify-center w-10 h-10 rounded bg-[#0d0d10] border border-zinc-800 shrink-0">
              {
                isCapturing
                  ? <Loader2 className="w-4 h-4 animate-spin text-blue-400" />
                  : <Camera className="w-4 h-4 text-zinc-500" />
              }
            </div>

            <div className="min-w-0 flex-1">
              <div className="text-xs font-medium truncate">
                {videoId ?? 'No video open'}
              </div>
              <div className="text-[10px] text-zinc-500 mt-0.5">
                {
                  hasVideo
                    ? 'Scrub and capture frames'
                    : 'No video.mp4 for this clip'
                }
              </div>
            </div>
          </button>
        </div>
      </Section>


      {/* ================================================= */}
      {/* FRAMES */}
      {/* ================================================= */}

      <Section
        title="Frames"
        count={frames.length}
        icon={Images}
        open={framesOpen}
        onToggle={() => setFramesOpen(!framesOpen)}
        grows
      >
        <div className="flex-1 min-h-0 overflow-y-auto p-2 space-y-1">

          {
            frames.length === 0
              ? (
                <div className="flex flex-col items-center justify-center gap-1 py-8 text-center text-zinc-600">
                  <span className="text-xs">No frames captured</span>
                  <span className="text-[10px]">
                    Open the video and capture one
                  </span>
                </div>
              )
              : frames.map(frame => {

                const isSelected =
                  selection.kind === 'frame' &&
                  selection.frameName === frame.name;

                const frameBoards = boards.filter(
                  board => board.frameName === frame.name
                );

                return (
                  <div
                    key={frame.name}
                    onClick={() =>
                      select({ kind: 'frame', frameName: frame.name })
                    }
                    className={`group flex items-center gap-2.5 p-2 rounded-lg cursor-pointer border transition-all ${
                      isSelected
                        ? 'bg-blue-600/10 border-blue-500 text-white'
                        : 'border-transparent hover:bg-[#1a1a1e] hover:text-zinc-100'
                    }`}
                  >
                    <div className="w-10 h-10 shrink-0 rounded overflow-hidden bg-[#0d0d10] border border-zinc-800">
                      {
                        videoId && (
                          // eslint-disable-next-line @next/next/no-img-element
                          <img
                            src={frameUrl(videoId, frame.name)}
                            alt={frame.name}
                            loading="lazy"
                            className="w-full h-full object-cover"
                          />
                        )
                      }
                    </div>

                    <div className="min-w-0 flex-1">
                      <div className="text-xs font-mono truncate">
                        {formatTimestamp(frame.timestampMs)}
                      </div>
                      <div className="text-[10px] text-zinc-500 mt-0.5">
                        {
                          frameBoards.length === 0
                            ? 'no boards'
                            : `${frameBoards.length} board${
                                frameBoards.length === 1 ? '' : 's'
                              }`
                        }
                      </div>
                    </div>

                    <button
                      onClick={event => {
                        event.stopPropagation();
                        onDeleteFrame(frame.name);
                      }}
                      title="Discard this frame and everything drawn on it"
                      className="p-1.5 rounded opacity-0 group-hover:opacity-100 hover:bg-red-500/10 text-zinc-500 hover:text-red-400 transition-all"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                );
              })
          }

        </div>
      </Section>


      {/* ================================================= */}
      {/* BOARDS */}
      {/* ================================================= */}

      <Section
        title="Boards"
        count={boards.length}
        icon={ScanLine}
        open={boardsOpen}
        onToggle={() => setBoardsOpen(!boardsOpen)}
        grows
      >
        <div className="flex-1 min-h-0 overflow-y-auto p-2 space-y-1">

          {
            boards.length === 0
              ? (
                <div className="flex flex-col items-center justify-center gap-1 py-8 text-center text-zinc-600">
                  <span className="text-xs">No boards drawn</span>
                  <span className="text-[10px]">
                    Box a price board on a frame
                  </span>
                </div>
              )
              : boards.map(board => {

                const isSelected =
                  selection.kind === 'board' &&
                  selection.boardId === board.id;

                const boardRegions = regions.filter(
                  region => region.boardId === board.id
                );

                const priced = boardRegions.filter(
                  region => region.price.trim() !== ''
                ).length;

                const Icon =
                  boardRegions.length === 0
                    ? Circle
                    : CheckCircle2;

                const iconColor =
                  boardRegions.length === 0
                    ? 'text-zinc-600'
                    : priced === boardRegions.length
                      ? 'text-green-500'
                      : 'text-blue-500';

                return (
                  <div
                    key={board.id}
                    onClick={() =>
                      select({ kind: 'board', boardId: board.id })
                    }
                    className={`flex items-center gap-2.5 p-2 rounded-lg cursor-pointer border transition-all ${
                      isSelected
                        ? 'bg-blue-600/10 border-blue-500 text-white'
                        : 'border-transparent hover:bg-[#1a1a1e] hover:text-zinc-100'
                    }`}
                  >
                    <Icon className={`w-3.5 h-3.5 shrink-0 ${iconColor}`} />

                    <div className="min-w-0 flex-1">
                      <div className="text-xs truncate">
                        {boardLabel(boards, board, frames)}
                      </div>
                      <div className="text-[10px] text-zinc-500 font-mono mt-0.5">
                        {
                          boardRegions.length === 0
                            ? 'no regions'
                            : `${priced}/${boardRegions.length} priced`
                        }
                      </div>
                    </div>
                  </div>
                );
              })
          }

        </div>
      </Section>

    </aside>
  );
};
