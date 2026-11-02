'use client';

import React, { useState } from 'react';

import { ChevronLeft, ChevronRight } from 'lucide-react';

import { VideoAssetName, VideoFolder } from '../../types';


interface ReferencePanelProps {
  videoId: string | null;
  crops: VideoAssetName[];
  prepped: VideoAssetName[];
}


interface SectionProps {
  title: string;
  hint: string;
  folder: VideoFolder;
  videoId: string;
  items: VideoAssetName[];
}


/**
 * One folder of the debug directory as a horizontal strip.
 *
 * These images vary a lot in aspect ratio, so scrolling sideways keeps every
 * one at full height rather than squashing them into a grid.
 */
const Section: React.FC<SectionProps> = ({
  title,
  hint,
  folder,
  videoId,
  items
}) => (

  <div className="flex flex-col min-h-0 flex-1">

    <div className="flex items-center justify-between px-3 h-8 shrink-0 bg-[#19191c] border-b border-[#232326]/50">
      <span
        title={hint}
        className="text-[10px] uppercase font-bold tracking-wider text-zinc-400 cursor-help"
      >
        {title}
      </span>

      <span className="font-mono text-[10px] text-zinc-600">
        {items.length}
      </span>
    </div>

    {
      items.length === 0
        ? (
          <div className="flex-1 flex items-center justify-center px-3 text-center text-[11px] text-zinc-600">
            None for this video
          </div>
        )
        : (
          <div className="flex-1 min-h-0 overflow-x-auto overflow-y-hidden p-2">
            <div className="flex gap-2 h-full">
              {
                items.map((item, index) => {

                  const url =
                    `/api/videos/${encodeURIComponent(videoId)}` +
                    `/file/${folder}/${encodeURIComponent(item.name)}`;

                  return (
                    <a
                      key={item.path}
                      href={url}
                      target="_blank"
                      rel="noreferrer"
                      // The filename is where the pipeline records what it
                      // thought: source frame, timestamp, confidence and any
                      // flags are all encoded in it.
                      title={`${item.name}\n\nOpen full size`}
                      className="group shrink-0 w-24 h-full flex flex-col"
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={url}
                        alt={item.name}
                        loading="lazy"
                        className="flex-1 min-h-0 w-full object-contain rounded border border-zinc-800 group-hover:border-blue-500 bg-[#0d0d10] transition-colors"
                      />

                      {/* Position in the strip, so one can be referred to by number. */}
                      <div className="shrink-0 pt-1 text-center text-[10px] font-mono text-zinc-500 group-hover:text-zinc-200">
                        {index + 1}
                      </div>
                    </a>
                  );
                })
              }
            </div>
          </div>
        )
    }

  </div>
);


/**
 * What the pipeline saw, next to what is being labelled.
 *
 * `crops` is what the board detector cut out and `prepped` is what went to the
 * OCR model, so between them they show why a reading came out the way it did.
 * Neither is annotated here, which is why they slide out over the canvas
 * instead of taking permanent space beside it.
 */
export const ReferencePanel: React.FC<ReferencePanelProps> = ({
  videoId,
  crops,
  prepped
}) => {

  const [isOpen, setIsOpen] = useState(false);

  const Arrow = isOpen ? ChevronLeft : ChevronRight;

  const total = crops.length + prepped.length;

  return (
    // `left-full` pins this to the outer edge of the sidebar whatever its
    // width, and floating it over the canvas means opening the panel never
    // resizes the drawing area.
    <div className="absolute left-full top-0 bottom-0 z-30 flex items-stretch pointer-events-none">

      <div
        className={`h-full bg-[#141416] border-r border-[#232326] shadow-2xl overflow-hidden transition-[width] duration-200 ease-out pointer-events-auto ${
          isOpen ? 'w-80' : 'w-0'
        }`}
      >
        {/* Kept mounted so thumbnails are not refetched on every open. */}
        <div className="w-80 h-full flex flex-col">

          <Section
            title="Crops"
            hint={
              'video-id/crops — what the board detector cut out of each ' +
              'frame. The filename carries the source frame, timestamp and ' +
              'confidence.'
            }
            folder="crops"
            videoId={videoId ?? ''}
            items={crops}
          />

          <div className="h-px bg-[#232326] shrink-0" />

          <Section
            title="Prepped"
            hint={
              'video-id/prepped — what was handed to the OCR model after ' +
              'processing. This is the image the reading came from.'
            }
            folder="prepped"
            videoId={videoId ?? ''}
            items={prepped}
          />

        </div>
      </div>

      {/* The tab itself, centred on the sidebar's outer edge and always there. */}
      <button
        onClick={() => setIsOpen(open => !open)}
        disabled={!videoId}
        title={
          isOpen
            ? 'Hide what the pipeline saw'
            : `Show what the pipeline saw — ${total} crop and prepped image(s)`
        }
        aria-expanded={isOpen}
        className="
          self-center
          pointer-events-auto
          flex
          items-center
          justify-center
          w-5
          h-20
          rounded-r
          border
          border-l-0
          border-[#232326]
          bg-[#1a1a1e]
          hover:bg-[#232328]
          disabled:opacity-40
          text-zinc-500
          hover:text-zinc-200
          shadow-lg
          transition-colors
        "
      >
        <Arrow className="w-3.5 h-3.5" />
      </button>

    </div>
  );
};
