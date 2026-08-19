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
  folder: VideoFolder;
  videoId: string;
  items: VideoAssetName[];
}


/**
 * What the pipeline produced for this video, alongside the frames being
 * annotated. These are reference only: boxes are never drawn on them, so they
 * live behind a tab rather than taking permanent space.
 */
const Section: React.FC<SectionProps> = ({
  title,
  folder,
  videoId,
  items
}) => (

  <div className="flex flex-col min-h-0 flex-1">

    <div className="flex items-center justify-between px-3 h-8 shrink-0 bg-[#19191c] border-b border-[#232326]/50 text-[10px] uppercase font-bold tracking-wider text-zinc-400">
      <span>{title}</span>
      <span className="font-mono text-zinc-600">{items.length}</span>
    </div>

    {items.length === 0 ? (
      <div className="flex-1 flex items-center justify-center text-[11px] text-zinc-600 px-3 text-center">
        None for this video
      </div>
    ) : (
      // A single horizontal strip: the images vary a lot in aspect ratio, so
      // scrolling sideways keeps every one at full height.
      <div className="flex-1 min-h-0 overflow-x-auto overflow-y-hidden p-2">
        <div className="flex gap-2 h-full">
          {items.map((item, index) => {
            const url =
              `/api/videos/${encodeURIComponent(videoId)}` +
              `/file/${folder}/${encodeURIComponent(item.name)}`;

            return (
              <a
                key={item.path}
                href={url}
                target="_blank"
                rel="noreferrer"
                title={`${index + 1}. ${item.name} — open full size`}
                className="group shrink-0 w-24 h-full flex flex-col"
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={url}
                  alt={`${title} ${index + 1}`}
                  loading="lazy"
                  className="flex-1 min-h-0 w-full object-contain rounded border border-zinc-800 group-hover:border-blue-500 bg-[#0d0d10] transition-colors"
                />

                {/* Position in the strip, so a frame can be referred to by number. */}
                <div className="shrink-0 pt-1 text-center text-[10px] font-mono text-zinc-500 group-hover:text-zinc-200">
                  {index + 1}
                </div>
              </a>
            );
          })}
        </div>
      </div>
    )}

  </div>
);


export const ReferencePanel: React.FC<ReferencePanelProps> = ({
  videoId,
  crops,
  prepped
}) => {

  const [isOpen, setIsOpen] = useState(false);

  const Arrow = isOpen ? ChevronLeft : ChevronRight;

  return (
    // `left-full` pins this to the right edge of the containing pane whatever
    // its width, and floating it over the canvas means opening the panel never
    // resizes the drawing area.
    <div className="absolute left-full top-0 bottom-0 z-30 flex items-stretch pointer-events-none">

      <div
        className={`h-full bg-[#141416] border-r border-[#232326] shadow-2xl overflow-hidden transition-[width] duration-200 ease-out pointer-events-auto ${
          isOpen ? 'w-72' : 'w-0'
        }`}
      >
        {/* Kept mounted so thumbnails are not refetched on every open. */}
        <div className="w-72 h-full flex flex-col">
          <Section
            title="Cropped frames"
            folder="crops"
            videoId={videoId ?? ''}
            items={crops}
          />

          <div className="h-px bg-[#232326] shrink-0" />

          <Section
            title="Prepped frames"
            folder="prepped"
            videoId={videoId ?? ''}
            items={prepped}
          />
        </div>
      </div>

      {/* The tab itself, always visible at the panel's outer edge. */}
      <button
        onClick={() => setIsOpen(open => !open)}
        title={
          isOpen
            ? 'Hide cropped and prepped frames'
            : 'Show cropped and prepped frames'
        }
        aria-expanded={isOpen}
        className="self-center pointer-events-auto flex items-center justify-center w-5 h-20 rounded-r border border-l-0 border-[#232326] bg-[#1a1a1e] hover:bg-[#232328] text-zinc-500 hover:text-zinc-200 shadow-lg transition-colors"
      >
        <Arrow className="w-3.5 h-3.5" />
      </button>

    </div>
  );
};
