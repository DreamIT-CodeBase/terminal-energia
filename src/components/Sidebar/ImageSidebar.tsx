'use client';

import React, { useMemo, useState, useEffect } from 'react';
import { useAnnotationStore } from '../../store/useAnnotationStore';
import { VideoSummary } from '../../types';
import {
  CheckCircle2, Circle, AlertCircle,
  ChevronLeft, ChevronRight, Search, X
} from 'lucide-react';


interface ImageSidebarProps {
  // Ordered newest first by the system API.
  videos: VideoSummary[];
  videoId: string | null;
  onVideoChange: (videoId: string) => void;
}


export const ImageSidebar: React.FC<ImageSidebarProps> = ({
  videos,
  videoId,
  onVideoChange
}) => {
  const {
    images,
    selectedImageId,
    annotations,
    stage,
    setSelectedImageId
  } = useAnnotationStore();

  const [searchQuery, setSearchQuery] = useState('');

  // The store only ever holds the images for the current stage: frames while
  // labelling boards, crops while labelling price regions.
  const stageImages = images;

  // Filter images by search term
  const filteredImages = useMemo(() => {
    return stageImages.filter(img =>
      img.name.toLowerCase().includes(searchQuery.toLowerCase())
    );
  }, [stageImages, searchQuery]);

  // Navigate images within the active folder
  const currentIndex = useMemo(() => {
    return stageImages.findIndex(img => img.id === selectedImageId);
  }, [stageImages, selectedImageId]);

  const selectNextImage = () => {
    if (currentIndex !== -1 && currentIndex < stageImages.length - 1) {
      setSelectedImageId(stageImages[currentIndex + 1].id);
    }
  };

  const selectPrevImage = () => {
    if (currentIndex > 0) {
      setSelectedImageId(stageImages[currentIndex - 1].id);
    }
  };

  // Videos are listed newest first, so the previous entry is the newer clip
  // and the next entry is the older one.
  const videoIndex = videos.findIndex(video => video.videoId === videoId);

  const newerVideo = videoIndex > 0 ? videos[videoIndex - 1] : null;

  const olderVideo =
    videoIndex !== -1 && videoIndex < videos.length - 1
      ? videos[videoIndex + 1]
      : null;

  // Keyboard navigation listener (left/right arrows)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const active = document.activeElement;
      const isInput = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA');
      if (isInput) return;

      if (e.key === 'ArrowRight') {
        selectNextImage();
      } else if (e.key === 'ArrowLeft') {
        selectPrevImage();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [stageImages, selectedImageId, currentIndex]);

  return (
    <aside className="flex flex-col h-full bg-[#141416] select-none text-zinc-300">
      {/* What this stage annotates */}
      <div className="px-3 h-9 flex items-center shrink-0 border-b border-[#232326] bg-[#19191c] text-[10px] uppercase font-bold tracking-wider text-zinc-400">
        {stage === 'board' ? 'Frames' : 'Board crops'}
      </div>

      {/* Image list search */}
      <div className="px-3 pt-3">
        <div className="relative">
          <Search className="absolute left-2.5 top-2.5 w-3.5 h-3.5 text-zinc-600" />
          <input
            type="text"
            placeholder="Search images..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-[#0d0d10] border border-[#232326] text-xs px-8 py-2 rounded-md outline-none focus:border-blue-500 text-zinc-200"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              className="absolute right-2 top-2.5 text-zinc-500 hover:text-zinc-300"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* Image list title */}
      <div className="flex items-center justify-between px-4 pt-3 pb-2 text-[10px] uppercase font-bold tracking-wider text-zinc-500">
        <span>Images ({filteredImages.length})</span>
        {stageImages.length > 0 && (
          <span>{currentIndex !== -1 ? currentIndex + 1 : 0}/{stageImages.length}</span>
        )}
      </div>

      {/* Thumbnails list */}
      <div className="flex-1 overflow-y-auto px-2 space-y-1">
        {filteredImages.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-10 text-center text-zinc-600">
            <span className="text-xs">No images matched</span>
          </div>
        ) : (
          filteredImages.map((img, idx) => {
            const isSelected = img.id === selectedImageId;
            const hasAnnotations = annotations.some(ann => ann.imageId === img.id);

            const ProgressIcon = img.progress === 'done'
              ? CheckCircle2
              : hasAnnotations
              ? AlertCircle
              : Circle;

            const progressColor = img.progress === 'done'
              ? 'text-green-500'
              : hasAnnotations
              ? 'text-blue-500'
              : 'text-zinc-600';

            return (
              <div
                key={img.id}
                onClick={() => setSelectedImageId(img.id)}
                className={`group flex items-center justify-between p-2 rounded-lg cursor-pointer transition-all border ${
                  isSelected
                    ? 'bg-blue-600/10 border-blue-500 text-white'
                    : 'border-transparent hover:bg-[#1a1a1e] hover:text-zinc-100'
                }`}
              >
                <div className="flex items-center gap-2.5 min-w-0 flex-1">
                  {/* Thumbnail Image */}
                  <div className="relative w-10 h-10 bg-[#0d0d10] border border-zinc-800 rounded overflow-hidden flex items-center justify-center shrink-0">
                    {img.url ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={img.url} alt={img.name} loading="lazy" className="w-full h-full object-cover" />
                    ) : (
                      <span className="text-[10px] text-zinc-600 font-mono">#{idx + 1}</span>
                    )}
                  </div>

                  {/* Filename & Info */}
                  <div className="min-w-0 flex-1">
                    <div className="text-xs truncate font-medium group-hover:text-zinc-100">
                      {img.name}
                    </div>
                    <div className="text-[10px] text-zinc-500 font-mono mt-0.5">
                      {img.width ? `${img.width}x${img.height}` : 'loading...'}
                    </div>
                  </div>
                </div>

                {/* Status indicator */}
                <div className="flex items-center gap-1.5 ml-2">
                  <ProgressIcon className={`w-3.5 h-3.5 ${progressColor}`} />
                </div>
              </div>
            );
          })
        )}
      </div>

      {/* Move between videos, newest first */}
      {videos.length > 1 && (
        <div className="border-t border-[#232326] bg-[#101012] shrink-0">
          <div className="flex items-center justify-between px-3 pt-2 text-[10px] uppercase font-bold tracking-wider text-zinc-600">
            <span>Video</span>
            <span className="font-mono">
              {videoIndex !== -1 ? videoIndex + 1 : 0}/{videos.length}
            </span>
          </div>

          <div className="flex items-center justify-between p-2 gap-1">
            <button
              onClick={() => newerVideo && onVideoChange(newerVideo.videoId)}
              disabled={!newerVideo}
              title={
                newerVideo
                  ? `Newer video: ${newerVideo.videoId}`
                  : 'Already on the newest video'
              }
              className="flex items-center justify-center p-1.5 rounded hover:bg-[#1a1a1e] disabled:opacity-30 disabled:hover:bg-transparent text-zinc-400 hover:text-white transition-all flex-1 min-w-0"
            >
              <ChevronLeft className="w-4 h-4 mr-0.5 shrink-0" />
              <span className="text-[11px] font-semibold">Prev</span>
            </button>

            <div className="w-px h-4 bg-[#232326] shrink-0" />

            <button
              onClick={() => olderVideo && onVideoChange(olderVideo.videoId)}
              disabled={!olderVideo}
              title={
                olderVideo
                  ? `Older video: ${olderVideo.videoId}`
                  : 'Already on the oldest video'
              }
              className="flex items-center justify-center p-1.5 rounded hover:bg-[#1a1a1e] disabled:opacity-30 disabled:hover:bg-transparent text-zinc-400 hover:text-white transition-all flex-1 min-w-0"
            >
              <span className="text-[11px] font-semibold">Next</span>
              <ChevronRight className="w-4 h-4 ml-0.5 shrink-0" />
            </button>
          </div>
        </div>
      )}

    </aside>
  );
};
