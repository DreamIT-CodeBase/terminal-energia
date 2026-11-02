'use client';

import React from 'react';

import { MapPinOff } from 'lucide-react';

import { Station } from '../../types';

import {
  formatDistance,
  hasPosition,
  stationLabel,
  STATION_COLOUR
} from './shared';


interface StationListProps {
  stations: Station[];
  selectedId: string | null;
  // Ids with an edit that has not been written yet, so the rail can mark
  // them alongside the panel.
  dirtyIds: Set<string>;
  // Metres from the searched coordinate, keyed by id. Empty unless the search
  // box holds a coordinate pair, in which case the rail is ordered by it.
  distances: Map<string, number>;
  onSelect: (stationId: string) => void;
}


// ============================================================
// STATION RAIL
//
// Every station the search matches, including the ones with no
// coordinates -- those cannot be drawn on the map, so the rail is
// the only way to reach them and give them a position.
// ============================================================

export const StationList: React.FC<StationListProps> = ({
  stations,
  selectedId,
  dirtyIds,
  distances,
  onSelect
}) => {

  if (stations.length === 0) {

    return (
      <div className="px-4 py-6 text-xs text-zinc-500">
        No stations match.
      </div>
    );
  }

  return (

    <ul className="p-2 space-y-0.5">

      {
        stations.map(station => {

          const isSelected = station.id === selectedId;

          const unplaced = !hasPosition(station);

          return (

            <li key={station.id}>

              <button
                onClick={() => onSelect(station.id)}
                className={`
                  w-full
                  flex
                  items-center
                  gap-2
                  px-2
                  py-1.5
                  rounded
                  text-left
                  transition-colors
                  ${
                    isSelected
                      ? 'bg-[#202024] text-zinc-100'
                      : 'text-zinc-400 hover:bg-[#1a1a1d] hover:text-zinc-200'
                  }
                `}
              >

                <span
                  className="w-2 h-2 rounded-full shrink-0"
                  style={{ background: STATION_COLOUR }}
                />

                <span className="flex-1 min-w-0">

                  <span className="block text-xs truncate">
                    {stationLabel(station)}
                  </span>

                  <span className="block text-[10px] text-zinc-600 truncate">
                    {station.brand || station.id}
                  </span>

                </span>

                {
                  distances.has(station.id) && (
                    <span className="text-[10px] text-blue-400 tabular-nums shrink-0">
                      {formatDistance(distances.get(station.id) as number)}
                    </span>
                  )
                }

                {
                  dirtyIds.has(station.id) && (
                    <span
                      title="Unsaved changes"
                      className="w-1.5 h-1.5 rounded-full bg-amber-400 shrink-0"
                    />
                  )
                }

                {
                  unplaced && (
                    <MapPinOff
                      className="w-3 h-3 text-zinc-600 shrink-0"
                      aria-label="No coordinates"
                    />
                  )
                }

              </button>

            </li>
          );
        })
      }

    </ul>
  );
};
