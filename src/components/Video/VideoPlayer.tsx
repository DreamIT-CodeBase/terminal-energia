'use client';

import React from 'react';
import { Film } from 'lucide-react';


interface VideoPlayerProps {
  videoId: string | null;
  // False when the video folder holds no video.mp4.
  hasVideo: boolean;
}


/**
 * The source clip the frames were extracted from, played with the browser's
 * own controls. Bytes are streamed through the session-protected API, so no
 * storage credentials reach the page.
 */
export const VideoPlayer: React.FC<VideoPlayerProps> = ({
  videoId,
  hasVideo
}) => {

  if (!videoId || !hasVideo) {
    return (
      <div className="flex flex-col items-center justify-center gap-2 h-full text-zinc-600">
        <Film className="w-5 h-5" />
        <span className="text-[11px]">No video for this clip</span>
      </div>
    );
  }

  return (
    <video
      // Remount on video change so the element picks up the new source.
      key={videoId}
      controls
      preload="metadata"
      className="w-full h-full bg-black object-contain"
    >
      <source
        src={`/api/videos/${encodeURIComponent(videoId)}/file/video.mp4`}
        type="video/mp4"
      />
      Your browser cannot play this video.
    </video>
  );
};
