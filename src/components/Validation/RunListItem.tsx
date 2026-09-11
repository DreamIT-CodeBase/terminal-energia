'use client';

import React, {
  useEffect,
  useState
} from 'react';

import { Film, ImageOff, Loader2 } from 'lucide-react';

import { VideoAssetName } from '../../types';
import { cropFileUrl } from './shared';


// ============================================================
// RUN LIST ITEM
//
// One run in the left rail: a single crop picked at random as a
// thumbnail, plus the video id. Crops are fetched lazily through
// the shared cache, so opening the run in the middle reuses them.
// ============================================================

interface RunListItemProps {
  videoId: string;
  selected: boolean;
  onSelect: () => void;
  // Shared, cached crop loader owned by the workspace.
  loadCrops: (videoId: string) => Promise<VideoAssetName[]>;
}


export const RunListItem: React.FC<RunListItemProps> = ({
  videoId,
  selected,
  onSelect,
  loadCrops
}) => {

  const [thumb, setThumb] = useState<VideoAssetName | null>(null);
  const [count, setCount] = useState<number | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'empty' | 'error'>(
    'loading'
  );


  useEffect(
    () => {

      let cancelled = false;

      loadCrops(videoId)
        .then(crops => {

          if (cancelled) return;

          setCount(crops.length);

          if (crops.length === 0) {
            setState('empty');
            return;
          }

          // A random crop each mount, so the rail is a quick sample of what
          // the run caught rather than always the first frame.
          const pick = crops[Math.floor(Math.random() * crops.length)];

          setThumb(pick);
          setState('ready');
        })
        .catch(() => {
          if (!cancelled) setState('error');
        });

      return () => {
        cancelled = true;
      };
    },
    [videoId, loadCrops]
  );


  return (

    <button
      onClick={onSelect}
      className={`
        flex items-center gap-3 w-full p-2 rounded-lg text-left transition-colors
        ${
          selected
            ? 'bg-blue-600/15 ring-1 ring-blue-500/60'
            : 'hover:bg-[#1c1c20]'
        }
      `}
    >

      {/* THUMBNAIL */}

      <div className="relative shrink-0 w-20 h-14 rounded-md overflow-hidden bg-[#0a0a0c] border border-[#232326] flex items-center justify-center">

        {
          state === 'loading' && (
            <Loader2 className="w-4 h-4 animate-spin text-zinc-600" />
          )
        }

        {
          (state === 'empty' || state === 'error') && (
            <ImageOff className="w-4 h-4 text-zinc-700" />
          )
        }

        {
          state === 'ready' && thumb && (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src={cropFileUrl(videoId, thumb.name)}
              alt={videoId}
              loading="lazy"
              className="w-full h-full object-cover"
            />
          )
        }

      </div>


      {/* LABEL */}

      <div className="min-w-0 flex-1">

        <div
          className={`text-xs font-mono truncate ${
            selected ? 'text-white' : 'text-zinc-300'
          }`}
          title={videoId}
        >
          {videoId}
        </div>

        <div className="flex items-center gap-1 mt-1 text-[11px] text-zinc-500">
          <Film className="w-3 h-3" />
          {
            count === null
              ? '…'
              : `${count} frame${count === 1 ? '' : 's'}`
          }
        </div>

      </div>

    </button>
  );
};
