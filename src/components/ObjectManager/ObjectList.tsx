'use client';

import React from 'react';

import {
  ScanLine,
  Square,
  Tag,
  Trash2
} from 'lucide-react';

import {
  boardLabel,
  useAnnotationStore
} from '../../store/useAnnotationStore';


/**
 * The boxes on whatever is open, and — for a board — the price each region
 * carries.
 *
 * Prices live here rather than on the canvas so a board with several grades
 * reads as one tab-navigable column, instead of inputs stacked on top of each
 * other over the crop.
 */
export const ObjectList: React.FC = () => {

  const {
    frames,
    boards,
    regions,
    selection,
    selectedAnnotationId,
    setSelectedAnnotationId,
    deleteAnnotation,
    setRegionPrice,
    select
  } = useAnnotationStore();


  // ============================================================
  // NOTHING OPEN
  // ============================================================

  if (selection.kind === 'video') {

    return (
      <div className="flex flex-col items-center justify-center gap-1 py-10 text-center text-zinc-600">
        <Tag className="w-7 h-7 text-zinc-700 mb-1" />
        <span className="text-xs">Nothing to label yet</span>
        <span className="text-[10px]">
          Capture a frame from the video first
        </span>
      </div>
    );
  }


  // ============================================================
  // A FRAME: THE BOARDS ON IT
  // ============================================================

  if (selection.kind === 'frame') {

    const frameBoards = boards.filter(
      board => board.frameName === selection.frameName
    );

    if (frameBoards.length === 0) {

      return (
        <div className="flex flex-col items-center justify-center gap-1 py-10 text-center text-zinc-500">
          <ScanLine className="w-7 h-7 text-zinc-700 mb-1" />
          <span className="text-xs">No boards on this frame</span>
          <span className="text-[10px] text-zinc-600">
            Draw a box around a price board
          </span>
        </div>
      );
    }

    return (
      <div className="flex-1 overflow-y-auto p-2 space-y-1.5">

        {
          frameBoards.map(board => {

            const isSelected = board.id === selectedAnnotationId;

            const count = regions.filter(
              region => region.boardId === board.id
            ).length;

            return (
              <div
                key={board.id}
                onClick={() => setSelectedAnnotationId(board.id)}
                className={`flex items-center justify-between p-2 rounded-lg cursor-pointer border transition-all ${
                  isSelected
                    ? 'bg-blue-600/10 border-blue-500 text-white'
                    : 'bg-[#141416] hover:bg-[#1a1a1e] border-transparent hover:border-zinc-800'
                }`}
              >
                <div className="flex items-center gap-2.5 min-w-0 flex-1">
                  <div
                    className="flex items-center justify-center w-6 h-6 rounded shrink-0"
                    style={{
                      backgroundColor: '#3B82F622',
                      color: '#3B82F6'
                    }}
                  >
                    <Square className="w-3.5 h-3.5" />
                  </div>

                  <div className="min-w-0 flex-1">
                    <div className="text-xs font-semibold truncate">
                      {boardLabel(boards, board, frames)}
                    </div>
                    <div className="text-[10px] text-zinc-500 mt-0.5">
                      {
                        count === 0
                          ? 'no price regions yet'
                          : `${count} price region${count === 1 ? '' : 's'}`
                      }
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-1 ml-2 shrink-0">
                  <button
                    onClick={event => {
                      event.stopPropagation();
                      select({ kind: 'board', boardId: board.id });
                    }}
                    title="Open this board to label its prices"
                    className="px-2 py-1 rounded text-[10px] font-semibold uppercase tracking-wide text-zinc-400 hover:bg-[#202024] hover:text-zinc-100 transition-colors"
                  >
                    Open
                  </button>

                  <button
                    onClick={event => {
                      event.stopPropagation();
                      deleteAnnotation(board.id);
                    }}
                    title="Delete this board and its price regions"
                    className="p-1.5 rounded hover:bg-red-500/10 text-zinc-500 hover:text-red-400 transition-colors"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            );
          })
        }

      </div>
    );
  }


  // ============================================================
  // A BOARD: ITS PRICE REGIONS, WITH THE PRICE ON EACH
  // ============================================================

  const boardRegions = regions.filter(
    region => region.boardId === selection.boardId
  );

  if (boardRegions.length === 0) {

    return (
      <div className="flex flex-col items-center justify-center gap-1 py-10 text-center text-zinc-500">
        <Tag className="w-7 h-7 text-zinc-700 mb-1" />
        <span className="text-xs">No price regions yet</span>
        <span className="text-[10px] text-zinc-600">
          Box each price on the board
        </span>
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-y-auto p-2 space-y-1.5">

      {
        boardRegions.map((region, index) => {

          const isSelected = region.id === selectedAnnotationId;

          return (
            <div
              key={region.id}
              onClick={() => setSelectedAnnotationId(region.id)}
              className={`p-2 rounded-lg cursor-pointer border transition-all ${
                isSelected
                  ? 'bg-blue-600/10 border-blue-500'
                  : 'bg-[#141416] hover:bg-[#1a1a1e] border-transparent hover:border-zinc-800'
              }`}
            >
              <div className="flex items-center gap-2">

                <div
                  className="flex items-center justify-center w-6 h-6 rounded shrink-0"
                  style={{
                    backgroundColor: '#EF444422',
                    color: '#EF4444'
                  }}
                >
                  <span className="text-[10px] font-mono font-bold">
                    {index + 1}
                  </span>
                </div>

                <input
                  // Numeric so a typo cannot reach the dataset, and decimal
                  // so 1.849 is as easy to type as it is to read.
                  type="number"
                  inputMode="decimal"
                  step="0.001"
                  min="0"
                  value={region.price}
                  placeholder="price"
                  onClick={event => event.stopPropagation()}
                  onFocus={() => setSelectedAnnotationId(region.id)}
                  onChange={event =>
                    setRegionPrice(region.id, event.target.value)
                  }
                  className="
                    flex-1
                    min-w-0
                    bg-[#0d0d10]
                    border
                    border-[#232326]
                    focus:border-blue-500
                    rounded
                    px-2
                    py-1.5
                    text-xs
                    font-mono
                    text-zinc-100
                    outline-none
                  "
                />

                <button
                  onClick={event => {
                    event.stopPropagation();
                    deleteAnnotation(region.id);
                  }}
                  title="Delete this price region"
                  className="p-1.5 rounded hover:bg-red-500/10 text-zinc-500 hover:text-red-400 transition-colors shrink-0"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>

              </div>
            </div>
          );
        })
      }

      <div className="px-1 pt-1 text-[10px] text-zinc-600">
        Leave a price empty if it cannot be read off the board.
      </div>

    </div>
  );
};
