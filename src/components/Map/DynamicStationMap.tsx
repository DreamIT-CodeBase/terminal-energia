'use client';

import dynamic from 'next/dynamic';

/**
 * Leaflet measures a real DOM element as it initialises, so the map can only
 * be built in the browser.
 */
export const DynamicStationMap = dynamic(
  () =>
    import('./StationMap').then(
      mod => mod.StationMap
    ),
  {
    ssr: false,
    loading: () => (
      <div
        className="
          w-full
          h-full
          flex
          items-center
          justify-center
          bg-[#0a0a0c]
          text-sm
          text-zinc-500
        "
      >
        Loading map…
      </div>
    )
  }
);
