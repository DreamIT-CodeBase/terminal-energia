'use client';

import React, {
  useCallback,
  useEffect,
  useState
} from 'react';

import { X } from 'lucide-react';

import { VideoAssetName } from '../../types';
import { cropFileUrl, parseCropMeta } from './shared';


// ============================================================
// CROP MODAL
//
// Opens a single crop full size over the page. Mount plays an
// enter transition (backdrop fades, image lifts in); closing
// plays it in reverse before the node leaves, so there is no
// hard cut in either direction.
// ============================================================

interface CropModalProps {
  videoId: string;
  crop: VideoAssetName | null;
  onClose: () => void;
}


export const CropModal: React.FC<CropModalProps> = ({
  videoId,
  crop,
  onClose
}) => {

  // The crop that is actually mounted. It lags `crop` on close so the exit
  // transition can finish before the node is torn down.
  const [mounted, setMounted] = useState<VideoAssetName | null>(crop);

  // Drives the enter/exit transition: false while sliding in or out, true once
  // settled open.
  const [shown, setShown] = useState(false);


  // Opening: mount immediately, then flip `shown` on the next frame so the
  // browser animates from the closed state rather than snapping to open.
  useEffect(
    () => {

      if (!crop) return;

      // Mounting the incoming crop and arming the enter transition is the
      // whole job of this effect; the render that follows is the closed state
      // the transition animates away from.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setMounted(crop);

      const raf = requestAnimationFrame(() => setShown(true));

      return () => cancelAnimationFrame(raf);
    },
    [crop]
  );


  // Closing: play the exit, then unmount once it has had time to run.
  useEffect(
    () => {

      if (crop) return;

      // Play the exit, then unmount once it has had time to run.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setShown(false);

      const timer = setTimeout(() => setMounted(null), 200);

      return () => clearTimeout(timer);
    },
    [crop]
  );


  // Esc closes, matching the backdrop click.
  const handleKey = useCallback(
    (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    },
    [onClose]
  );

  useEffect(
    () => {

      if (!crop) return;

      window.addEventListener('keydown', handleKey);

      return () => window.removeEventListener('keydown', handleKey);
    },
    [crop, handleKey]
  );


  if (!mounted) return null;

  const meta = parseCropMeta(mounted.name);

  return (

    <div
      onClick={onClose}
      className={`
        fixed inset-0 z-50 flex items-center justify-center p-8
        bg-black/80 backdrop-blur-sm
        transition-opacity duration-200 ease-out
        ${shown ? 'opacity-100' : 'opacity-0'}
      `}
    >

      <div
        onClick={event => event.stopPropagation()}
        className={`
          relative flex flex-col items-center max-w-[92vw] max-h-[92vh]
          transition-all duration-200 ease-out
          ${shown ? 'opacity-100 scale-100 translate-y-0' : 'opacity-0 scale-95 translate-y-2'}
        `}
      >

        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={cropFileUrl(videoId, mounted.name)}
          alt={mounted.name}
          className="max-w-full max-h-[80vh] object-contain rounded-lg shadow-2xl bg-[#0a0a0c]"
        />

        {/* WHAT THIS CROP IS, READ OFF ITS NAME */}

        <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-1 mt-3 text-xs text-zinc-300">
          {
            meta.timeS !== null && (
              <span>
                <span className="text-zinc-500">t </span>
                <span className="font-mono">{meta.timeS.toFixed(2)}s</span>
              </span>
            )
          }
          {
            meta.frame !== null && (
              <span>
                <span className="text-zinc-500">frame </span>
                <span className="font-mono">{meta.frame}</span>
              </span>
            )
          }
          {
            meta.board !== null && (
              <span>
                <span className="text-zinc-500">board </span>
                <span className="font-mono">{meta.board}</span>
              </span>
            )
          }
          {
            meta.confidence !== null && (
              <span>
                <span className="text-zinc-500">conf </span>
                <span className="font-mono">{meta.confidence.toFixed(2)}</span>
              </span>
            )
          }
        </div>

        <div className="mt-1 px-1 text-[11px] font-mono text-zinc-500 truncate max-w-full">
          {mounted.name}
        </div>

        <button
          onClick={onClose}
          title="Close (Esc)"
          className="absolute -top-3 -right-3 flex items-center justify-center w-9 h-9 rounded-full bg-[#1c1c20] border border-[#303036] text-zinc-300 hover:text-white hover:bg-[#26262b] shadow-lg"
        >
          <X className="w-4 h-4" />
        </button>

      </div>

    </div>
  );
};
