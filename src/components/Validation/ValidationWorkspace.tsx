'use client';

import React, {
  useCallback,
  useEffect,
  useRef,
  useState
} from 'react';

import Link from 'next/link';

import {
  ArrowLeft,
  ImageOff,
  Loader2,
  RefreshCw
} from 'lucide-react';

import { Station, VideoAssets, VideoAssetName } from '../../types';

import {
  belongsToValidationFolder,
  compareCrops,
  cropFileUrl,
  EnrichedStation,
  parseCropMeta,
  readError,
  STATION_COORDINATE_TOLERANCE,
  VALIDATION_FROM_DATE
} from './shared';

import { RunListItem } from './RunListItem';
import { CropModal } from './CropModal';
import { ResponseJson } from './ResponseJson';


interface ValidationWorkspaceProps {
  userEmail: string;
}


// ============================================================
// VALIDATION WORKSPACE
//
// Three panes. Left: the runs for the two validation folders,
// each with a random frame as a thumbnail. Middle: every frame
// of the selected run, any of which opens full size. Right: the
// pipeline's response.json for that run.
// ============================================================

export const ValidationWorkspace: React.FC<ValidationWorkspaceProps> = ({
  userEmail
}) => {

  const [videoIds, setVideoIds] = useState<string[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // Crop count per run, filled in as each run's crops resolve. A run stays out
  // of the rail until its count is known and above zero, so runs with no frames
  // never show. `-1` while a count is still in flight.
  const [cropCounts, setCropCounts] = useState<Record<string, number>>({});

  const [crops, setCrops] = useState<VideoAssetName[]>([]);
  const [apiResponse, setApiResponse] = useState<unknown>(null);
  const [stationMapping, setStationMapping] = useState<Station[]>([]);
  const [enrichedStations, setEnrichedStations] = useState<EnrichedStation[]>([]);

  const [isLoadingRuns, setIsLoadingRuns] = useState(true);
  const [isLoadingCrops, setIsLoadingCrops] = useState(false);
  const [isLoadingResponse, setIsLoadingResponse] = useState(false);
  const [runsError, setRunsError] = useState('');

  const [modalCrop, setModalCrop] = useState<VideoAssetName | null>(null);


  // ============================================================
  // SHARED CROP CACHE
  //
  // The left rail loads a run's crops to pick a thumbnail and the
  // middle loads them to lay the whole run out. One cache serves
  // both, so opening a run the rail already sampled is instant, and
  // each run is fetched at most once even while thumbnails race.
  // ============================================================

  const cacheRef = useRef<Map<string, VideoAssetName[]>>(new Map());
  const inflightRef = useRef<Map<string, Promise<VideoAssetName[]>>>(new Map());

  const loadCrops = useCallback(
    (videoId: string): Promise<VideoAssetName[]> => {

      const cached = cacheRef.current.get(videoId);

      if (cached) return Promise.resolve(cached);

      const inflight = inflightRef.current.get(videoId);

      if (inflight) return inflight;

      const request = (async () => {

        try {

          const response = await fetch(
            `/api/videos/${encodeURIComponent(videoId)}`,
            { cache: 'no-store' }
          );

          if (!response.ok) {
            throw new Error(await readError(response));
          }

          const assets: VideoAssets = await response.json();

          const loaded = [...(assets.folders?.crops ?? [])].sort(compareCrops);

          cacheRef.current.set(videoId, loaded);

          return loaded;

        } finally {
          inflightRef.current.delete(videoId);
        }
      })();

      inflightRef.current.set(videoId, request);

      return request;
    },
    []
  );


  // ============================================================
  // LOAD THE RUN LIST
  // ============================================================

  const loadRuns = useCallback(
    async () => {

      setIsLoadingRuns(true);
      setRunsError('');
      // A reload re-checks every run's crop count from scratch.
      setCropCounts({});
      cacheRef.current.clear();

      try {

        const response = await fetch('/api/videos', { cache: 'no-store' });

        if (!response.ok) {
          throw new Error(await readError(response));
        }

        const data: {
          videos: { video_id: string; has_video?: boolean }[];
        } = await response.json();

        const ids = (data.videos ?? [])
          .map(video => video.video_id)
          // Only the two validation folders, and only runs with a clip behind
          // them so there are frames to show.
          .filter(belongsToValidationFolder);

        setVideoIds(ids);

      } catch (error) {

        console.error('Run list error:', error);

        setRunsError(
          error instanceof Error
            ? error.message
            : 'Unable to reach the system API.'
        );

      } finally {
        setIsLoadingRuns(false);
      }
    },
    []
  );


  useEffect(
    () => {
      // Fetching the run list on mount is the whole point of this effect, and
      // the loading flag it sets is what the first render waits on.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      loadRuns();
    },
    [loadRuns]
  );


  useEffect(
    () => {

      let cancelled = false;

      const cached = window.localStorage.getItem('fp-validation:stations');

      if (cached) {
        try {
          const data: { stations?: Station[] } = JSON.parse(cached);
          // Restore the cached mapping before the network refresh completes.
          // eslint-disable-next-line react-hooks/set-state-in-effect
          setStationMapping(data.stations ?? []);
        } catch {
          window.localStorage.removeItem('fp-validation:stations');
        }
      }

      fetch('/api/stations', { cache: 'no-store' })
        .then(async response => {
          if (!response.ok) {
            throw new Error(await readError(response));
          }

          const data: { stations?: Station[] } = await response.json();

          try {
            window.localStorage.setItem(
              'fp-validation:stations',
              JSON.stringify(data)
            );
          } catch {
            // Browser storage is optional; the mapping endpoint remains usable.
          }

          if (!cancelled) {
            setStationMapping(data.stations ?? []);
          }
        })
        .catch(error => {
          console.error('Station mapping cache error:', error);
        });

      fetch('/api/stations-enriched', { cache: 'no-store' })
        .then(async response => {
          if (!response.ok) {
            throw new Error(await readError(response));
          }

          const data: { stations?: EnrichedStation[] } = await response.json();

          if (!cancelled) {
            setEnrichedStations(data.stations ?? []);
          }
        })
        .catch(error => {
          // Enriched data is optional. The response JSON remains the fallback.
          console.error('Enriched station list error:', error);
        });

      return () => {
        cancelled = true;
      };
    },
    []
  );

  const updateEnrichedStation = useCallback(
    (updated: EnrichedStation) => {
      setEnrichedStations(current => {
        const index = current.findIndex(station =>
          typeof station.latitude === 'number' &&
          typeof station.longitude === 'number' &&
          typeof updated.latitude === 'number' &&
          typeof updated.longitude === 'number' &&
          Math.abs(station.latitude - updated.latitude) <=
            STATION_COORDINATE_TOLERANCE &&
          Math.abs(station.longitude - updated.longitude) <=
            STATION_COORDINATE_TOLERANCE
        );

        return index < 0
          ? [...current, updated]
          : current.map((station, stationIndex) =>
              stationIndex === index ? updated : station
            );
      });
    },
    []
  );


  // ============================================================
  // RESOLVE CROP COUNTS
  //
  // `/api/videos` does not say how many crops a run has, so each is
  // asked (through the shared cache, so the rail's thumbnails reuse
  // the same fetch). A run only enters the rail once its count is
  // known and above zero, which is how runs with no frames are kept
  // off the UI entirely.
  // ============================================================

  useEffect(
    () => {

      if (videoIds.length === 0) return;

      let cancelled = false;

      for (const videoId of videoIds) {

        loadCrops(videoId)
          .then(loaded => {
            if (cancelled) return;
            setCropCounts(prev => ({ ...prev, [videoId]: loaded.length }));
          })
          .catch(() => {
            // Treat an unreadable run as empty so it stays hidden rather than
            // hanging in an unknown state.
            if (cancelled) return;
            setCropCounts(prev => ({ ...prev, [videoId]: 0 }));
          });
      }

      return () => {
        cancelled = true;
      };
    },
    [videoIds, loadCrops]
  );


  // Newest-first order preserved; a run shows only once it is known to have at
  // least one crop.
  const visibleIds = videoIds.filter(id => (cropCounts[id] ?? -1) > 0);

  // Every run's count is in, so an empty rail really is empty rather than still
  // resolving.
  const allCountsResolved =
    videoIds.length > 0 &&
    videoIds.every(id => cropCounts[id] !== undefined);


  // The selection at the moment a request settles, so a stale response for a
  // run the user has clicked away from is dropped instead of flashing in. Set
  // in `openRun` (an event handler), never during render.
  const selectedRef = useRef<string | null>(null);

  const isStale = (videoId: string) => selectedRef.current !== videoId;


  // ============================================================
  // OPEN A RUN
  //
  // Crops come from the shared cache; the response is fetched
  // fresh each time, since it is only needed once a run is open.
  // ============================================================

  const openRun = useCallback(
    async (videoId: string) => {

      selectedRef.current = videoId;

      setSelectedId(videoId);
      setCrops([]);
      setApiResponse(null);
      setModalCrop(null);
      setIsLoadingCrops(true);
      setIsLoadingResponse(true);

      // Crops.
      loadCrops(videoId)
        .then(loaded => {
          // A faster click may have moved on already.
          if (isStale(videoId)) return;
          setCrops(loaded);
        })
        .catch(() => {
          if (isStale(videoId)) return;
          setCrops([]);
        })
        .finally(() => {
          if (isStale(videoId)) return;
          setIsLoadingCrops(false);
        });

      // response.json.
      fetch(
        `/api/videos/${encodeURIComponent(videoId)}/api-response`,
        { cache: 'no-store' }
      )
        .then(async response => {
          if (isStale(videoId)) return;
          // A missing response.json is a 404, which is not an error here.
          setApiResponse(response.ok ? await response.json() : null);
        })
        .catch(() => {
          if (isStale(videoId)) return;
          setApiResponse(null);
        })
        .finally(() => {
          if (isStale(videoId)) return;
          setIsLoadingResponse(false);
        });
    },
    // `loadCrops` is stable; the guard reads the latest selection off a ref.
    [loadCrops]
  );


  // ============================================================
  // FIRST LOAD
  // ============================================================

  if (isLoadingRuns) {

    return (
      <div className="w-screen h-screen flex flex-col items-center justify-center gap-3 bg-[#0d0d10] text-zinc-300">
        <Loader2 className="w-8 h-8 animate-spin text-blue-400" />
        <div>Loading runs…</div>
      </div>
    );
  }


  // ============================================================
  // WORKSPACE
  // ============================================================

  return (

    <div className="flex flex-col h-screen overflow-hidden bg-[#0d0d10] text-zinc-200">

      {/* ===================================================== */}
      {/* HEADER */}
      {/* ===================================================== */}

      <header className="flex items-center justify-between h-14 px-4 bg-[#141416] border-b border-[#232326] shrink-0 select-none">

        <div className="flex items-center gap-3">

          <div className="flex items-center gap-2">
            <div className="flex items-center justify-center w-7 h-7 rounded bg-[#00bcd4] font-bold text-black text-xs">
              FP
            </div>
            <span className="text-xs font-black tracking-widest text-white uppercase hidden lg:inline">
              Fuel Price Validation
            </span>
          </div>

          <Link
            href="/annotation"
            className="flex items-center gap-1.5 border-l border-zinc-800 pl-3 text-xs text-zinc-400 hover:text-zinc-100 transition-colors"
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            Annotation
          </Link>

        </div>

        <div className="flex items-center gap-3">

          <span className="text-[11px] text-zinc-500">
            From {VALIDATION_FROM_DATE} · {visibleIds.length} runs
            {!allCountsResolved && !runsError && videoIds.length > 0 && '…'}
          </span>

          <button
            onClick={loadRuns}
            title="Reload runs"
            className="p-1.5 rounded hover:bg-[#202024] text-zinc-400 hover:text-zinc-200"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </button>

          <span
            className="hidden xl:inline text-[11px] text-zinc-500 max-w-[180px] truncate border-l border-zinc-800 pl-3"
            title={userEmail}
          >
            {userEmail}
          </span>

        </div>

      </header>


      <div className="flex flex-1 min-h-0">

        {/* ================================================= */}
        {/* LEFT: RUNS */}
        {/* ================================================= */}

        <aside className="w-72 shrink-0 flex flex-col h-full bg-[#141416] border-r border-[#232326]">

          <div className="flex items-center px-4 h-12 border-b border-[#232326] shrink-0">
            <span className="text-xs font-semibold uppercase tracking-wider text-zinc-400">
              Runs
            </span>
          </div>

          <div className="flex-1 min-h-0 overflow-y-auto p-2 space-y-1">

            {
              runsError && (
                <div className="m-2 rounded-lg border border-red-500/40 bg-red-500/10 px-3 py-2 text-[11px] text-red-300">
                  {runsError}
                </div>
              )
            }

            {
              !runsError && visibleIds.length === 0 && !allCountsResolved && (
                <div className="flex items-center justify-center gap-2 px-3 py-8 text-[11px] text-zinc-600">
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  Checking runs…
                </div>
              )
            }

            {
              !runsError && visibleIds.length === 0 && allCountsResolved && (
                <div className="px-3 py-8 text-center text-[11px] text-zinc-600">
                  No runs with frames from {VALIDATION_FROM_DATE} onwards.
                </div>
              )
            }

            {
              visibleIds.map(videoId => (
                <RunListItem
                  key={videoId}
                  videoId={videoId}
                  selected={videoId === selectedId}
                  onSelect={() => openRun(videoId)}
                  loadCrops={loadCrops}
                />
              ))
            }

          </div>

        </aside>


        {/* ================================================= */}
        {/* MIDDLE: CROPS */}
        {/* ================================================= */}

        <main className="flex-1 min-w-0 h-full overflow-y-auto bg-[#0a0a0c]">

          {
            !selectedId
              ? (
                <div className="flex items-center justify-center h-full px-8 text-center text-zinc-600 text-sm">
                  Pick a run on the left to see its frames.
                </div>
              )
              : isLoadingCrops
                ? (
                  <div className="flex flex-col items-center justify-center h-full gap-3 text-zinc-400">
                    <Loader2 className="w-6 h-6 animate-spin text-blue-400" />
                    <div>Loading frames…</div>
                  </div>
                )
                : crops.length === 0
                  ? (
                    <div className="flex flex-col items-center justify-center h-full gap-3 text-zinc-600">
                      <ImageOff className="w-6 h-6" />
                      <div className="text-sm">No frames for this run.</div>
                    </div>
                  )
                  : (
                    <div className="p-4">

                      <div className="flex items-center gap-2 mb-4 px-1">
                        <span className="text-sm font-mono text-zinc-200">
                          {selectedId}
                        </span>
                        <span className="text-xs text-zinc-500">
                          · {crops.length} frame{crops.length === 1 ? '' : 's'}
                        </span>
                      </div>

                      <div className="grid grid-cols-[repeat(auto-fill,minmax(180px,1fr))] gap-3">
                        {
                          crops.map(crop => {

                            const meta = parseCropMeta(crop.name);

                            return (
                              <button
                                key={crop.name}
                                onClick={() => setModalCrop(crop)}
                                className="group flex flex-col rounded-lg overflow-hidden bg-[#141416] border border-[#232326] hover:border-blue-500/60 transition-colors text-left"
                              >
                                <div className="aspect-video bg-[#0a0a0c] overflow-hidden flex items-center justify-center">
                                  {/* eslint-disable-next-line @next/next/no-img-element */}
                                  <img
                                    src={cropFileUrl(selectedId, crop.name)}
                                    alt={crop.name}
                                    loading="lazy"
                                    className="max-w-full max-h-full object-contain transition-transform duration-200 group-hover:scale-[1.03]"
                                  />
                                </div>

                                <div className="px-2 py-1.5">
                                  <div className="flex items-center gap-2 text-[11px] text-zinc-300">
                                    {
                                      meta.timeS !== null && (
                                        <span className="font-mono">
                                          {meta.timeS.toFixed(2)}s
                                        </span>
                                      )
                                    }
                                    {
                                      meta.board !== null && (
                                        <span className="text-zinc-500">
                                          board {meta.board}
                                        </span>
                                      )
                                    }
                                    {
                                      meta.confidence !== null && (
                                        <span className="ml-auto text-zinc-500">
                                          c{meta.confidence.toFixed(2)}
                                        </span>
                                      )
                                    }
                                  </div>
                                  <div className="mt-0.5 text-[10px] font-mono text-zinc-600 truncate">
                                    {crop.name}
                                  </div>
                                </div>
                              </button>
                            );
                          })
                        }
                      </div>

                    </div>
                  )
          }

        </main>


        {/* ================================================= */}
        {/* RIGHT: RESPONSE JSON */}
        {/* ================================================= */}

        <ResponseJson
          apiResponse={apiResponse}
          videoId={selectedId}
          isLoading={Boolean(selectedId) && isLoadingResponse}
          hasSelection={Boolean(selectedId)}
          stations={enrichedStations}
          stationMapping={stationMapping}
          onEnrichedStationSaved={updateEnrichedStation}
        />

      </div>


      {/* MODAL */}

      {
        selectedId && (
          <CropModal
            videoId={selectedId}
            crop={modalCrop}
            onClose={() => setModalCrop(null)}
          />
        )
      }

    </div>
  );
};
