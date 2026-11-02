'use client';

import React, {
  useCallback,
  useEffect,
  useMemo,
  useState
} from 'react';

import Link from 'next/link';

import {
  ArrowLeft,
  Loader2,
  MapPin,
  Plus,
  RefreshCw,
  Search
} from 'lucide-react';

import {
  Station,
  StationDeleteResponse,
  StationListResponse,
  StationUpsertResponse,
  StationWrite
} from '../../types';

import { DynamicStationMap } from './DynamicStationMap';
import { StationList } from './StationList';
import { StationPanel } from './StationPanel';

import {
  compareStations,
  distanceMetres,
  formatCoordinate,
  matchesQuery,
  parseCoordinates,
  Point,
  readError,
  stationIdFromName,
  stationPoint
} from './shared';


interface MapWorkspaceProps {
  userEmail: string;
}


/** The id a draft new station carries until it is given a name. */
const NEW_DRAFT_ID = '';


const emptyDraft = (): Station => ({
  id: NEW_DRAFT_ID,
  name: '',
  latitude: null,
  longitude: null,
  brand: '',
  modified_time: null,
  fuel_types: null,
  note: ''
});


/**
 * Which fields to send. Everything the panel can edit is sent on every save,
 * including the ones left empty: the API merges, so omitting a field would
 * quietly keep the old value where the intent was to clear it. Empty text
 * becomes null rather than an empty string, so a cleared field reads as
 * absent in the document like every other unset field does.
 */
const writeBody = (
  draft: Station
): StationWrite => {

  const text = (
    value: string | null
  ): string | null => {

    const trimmed = value?.trim() ?? '';

    return trimmed === '' ? null : trimmed;
  };

  return {
    name: text(draft.name),
    brand: text(draft.brand),
    note: text(draft.note),
    latitude: draft.latitude,
    longitude: draft.longitude,
    fuel_types: draft.fuel_types
  };
};


const sameStation = (
  a: Station,
  b: Station
): boolean =>
  JSON.stringify(writeBody(a)) === JSON.stringify(writeBody(b));


// ============================================================
// MAP WORKSPACE
//
// Three panes, like the other two workspaces. Left: every station,
// searchable, including those with no coordinates. Middle: the map.
// Right: the selected station, or the one being added.
//
// Edits are local until saved. The write merges server-side, so a
// device upserting the same station between the load and the save
// keeps whatever fields this never touched.
// ============================================================

export const MapWorkspace: React.FC<MapWorkspaceProps> = ({
  userEmail
}) => {

  const [stations, setStations] = useState<Station[]>([]);
  const [etag, setEtag] = useState<string | null>(null);

  // The capture trigger's enter_radius_m from the device config, drawn as a
  // ring around every station. Null until it loads, or if the config is absent.
  const [triggerRadiusM, setTriggerRadiusM] = useState<number | null>(null);

  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const [query, setQuery] = useState('');

  const [selectedId, setSelectedId] = useState<string | null>(null);

  // The station being edited, held apart from the loaded list so a reload of
  // the list cannot pull the ground out from under an unsaved edit.
  const [draft, setDraft] = useState<Station | null>(null);
  const [isNew, setIsNew] = useState(false);

  const [isPlacing, setIsPlacing] = useState(false);

  const [isSaving, setIsSaving] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [panelError, setPanelError] = useState('');

  // Confirmation of the last write, shown in the header rather than as a
  // dialog so it does not interrupt the next edit.
  const [status, setStatus] = useState('');


  // ============================================================
  // LOAD
  // ============================================================

  const loadStations = useCallback(
    async () => {

      setIsLoading(true);
      setLoadError('');

      try {

        const response = await fetch(
          '/api/stations',
          { cache: 'no-store' }
        );

        if (!response.ok) {
          throw new Error(await readError(response));
        }

        const data: StationListResponse = await response.json();

        setStations(data.stations ?? []);
        setEtag(data.etag ?? null);

      } catch (error) {
        setLoadError(
          error instanceof Error
            ? error.message
            : 'The station list could not be loaded.'
        );

      } finally {
        setIsLoading(false);
      }
    },
    []
  );

  useEffect(
    () => {
      // Fetching the station list on mount is the whole point of this effect,
      // and the loading flag it sets is what the first render waits on.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void loadStations();
    },
    [loadStations]
  );


  // ============================================================
  // TRIGGER RADIUS
  //
  // The device config's capture.trigger.enter_radius_m, drawn on
  // the map. Read once and independent of the station list, so a
  // missing config just leaves the rings off rather than blocking
  // the map.
  // ============================================================

  useEffect(
    () => {

      let cancelled = false;

      void (async () => {

        try {

          const response = await fetch(
            '/api/device-config',
            { cache: 'no-store' }
          );

          if (!response.ok) return;

          const data = await response.json();

          const radius =
            data?.config?.capture?.trigger?.enter_radius_m;

          if (!cancelled && typeof radius === 'number' && radius > 0) {
            setTriggerRadiusM(radius);
          }

        } catch {
          // The rings are supplementary; a failed read just omits them.
        }
      })();

      return () => {
        cancelled = true;
      };
    },
    []
  );


  // ============================================================
  // SELECTION
  // ============================================================

  const stored = useMemo(
    () =>
      stations.find(station => station.id === selectedId) ?? null,
    [stations, selectedId]
  );

  const isDirty = Boolean(
    draft &&
    (isNew || !stored || !sameStation(draft, stored))
  );

  const select = useCallback(
    (stationId: string | null) => {

      if (
        isDirty &&
        !window.confirm(
          'Discard the unsaved changes to this station?'
        )
      ) {
        return;
      }

      setPanelError('');
      setIsPlacing(false);
      setIsNew(false);

      setSelectedId(stationId);

      setDraft(
        stationId
          ? stations.find(station => station.id === stationId) ?? null
          : null
      );
    },
    [isDirty, stations]
  );


  const startNew = useCallback(
    () => {

      if (
        isDirty &&
        !window.confirm(
          'Discard the unsaved changes to this station?'
        )
      ) {
        return;
      }

      setPanelError('');
      setSelectedId(null);
      setDraft(emptyDraft());
      setIsNew(true);
      // Straight into placing: a new station needs a position before it can
      // be saved, and the map click is the natural way to give it one.
      setIsPlacing(true);
    },
    [isDirty]
  );


  // ============================================================
  // EDIT
  // ============================================================

  const patchDraft = useCallback(
    (patch: Partial<Station>) => {

      setPanelError('');

      setDraft(current => {

        if (!current) return current;

        const next = { ...current, ...patch };

        // For a new station the id follows the name until it is saved, so the
        // key it will be stored under is visible while it is being typed.
        if (isNew && 'name' in patch) {
          next.id = stationIdFromName(next.name ?? '');
        }

        return next;
      });
    },
    [isNew]
  );


  /** A marker drag. Only the selected station is draggable. */
  const moveStation = useCallback(
    (
      stationId: string,
      latitude: number,
      longitude: number
    ) => {

      if (stationId !== selectedId) return;

      patchDraft({ latitude, longitude });
    },
    [selectedId, patchDraft]
  );


  const placeAt = useCallback(
    (
      latitude: number,
      longitude: number
    ) => {

      patchDraft({ latitude, longitude });

      setIsPlacing(false);
    },
    [patchDraft]
  );


  const revert = useCallback(
    () => {

      setPanelError('');
      setIsPlacing(false);

      if (isNew) {
        setDraft(emptyDraft());
        return;
      }

      setDraft(stored);
    },
    [isNew, stored]
  );


  // ============================================================
  // SAVE
  //
  // An add and an edit are the same call: the id is the key, and
  // the API creates or amends on it. The response says which it
  // did, and carries the entry as stored.
  // ============================================================

  const idConflict =
    isNew &&
    Boolean(draft?.id) &&
    stations.some(station => station.id === draft?.id);


  const save = useCallback(
    async () => {

      if (!draft || !draft.id) return;

      setIsSaving(true);
      setPanelError('');

      try {

        const response = await fetch(
          `/api/stations/${encodeURIComponent(draft.id)}`,
          {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(writeBody(draft))
          }
        );

        if (!response.ok) {
          throw new Error(await readError(response));
        }

        const data: StationUpsertResponse = await response.json();

        setStations(current => {

          const without = current.filter(
            station => station.id !== data.station.id
          );

          return [...without, data.station];
        });

        setEtag(data.etag);

        setSelectedId(data.station.id);
        setDraft(data.station);
        setIsNew(false);

        setStatus(
          data.created
            ? `Added ${data.station.name || data.station.id}.`
            : `Saved ${data.station.name || data.station.id}.`
        );

      } catch (error) {
        setPanelError(
          error instanceof Error
            ? error.message
            : 'The station could not be saved.'
        );

      } finally {
        setIsSaving(false);
      }
    },
    [draft]
  );


  // ============================================================
  // DELETE
  //
  // The response carries the removed entry and is the only copy
  // left, so it is kept in the status line long enough to be read.
  // ============================================================

  const remove = useCallback(
    async () => {

      if (!draft || !draft.id || isNew) return;

      setIsDeleting(true);
      setPanelError('');

      try {

        const response = await fetch(
          `/api/stations/${encodeURIComponent(draft.id)}`,
          { method: 'DELETE' }
        );

        if (!response.ok) {
          throw new Error(await readError(response));
        }

        const data: StationDeleteResponse = await response.json();

        setStations(current =>
          current.filter(station => station.id !== data.station.id)
        );

        setEtag(data.etag);

        setSelectedId(null);
        setDraft(null);

        setStatus(
          `Removed ${data.station.name || data.station.id}.`
        );

      } catch (error) {
        setPanelError(
          error instanceof Error
            ? error.message
            : 'The station could not be removed.'
        );

      } finally {
        setIsDeleting(false);
      }
    },
    [draft, isNew]
  );


  // ============================================================
  // WHAT THE MAP AND THE RAIL SEE
  //
  // The draft stands in for its stored entry, so an edited name or
  // a dragged position shows everywhere at once rather than only
  // in the panel.
  // ============================================================

  /**
   * A complete coordinate pair in the search box is a place to look rather
   * than text to match, so it changes what the rail means: every station,
   * nearest first, instead of the ones whose fields contain the string.
   */
  const searchPoint = useMemo(
    () => parseCoordinates(query),
    [query]
  );


  const visible = useMemo(
    () => {

      const merged =
        draft && !isNew
          ? stations.map(
              station =>
                station.id === draft.id ? draft : station
            )
          : stations;

      if (searchPoint) {

        // Only what can be measured. A station with no coordinates has no
        // distance to the searched point, so it has no place in this order.
        return merged
          .filter(station => stationPoint(station) !== null)
          .sort(
            (a, b) =>
              distanceMetres(searchPoint, stationPoint(a) as Point) -
              distanceMetres(searchPoint, stationPoint(b) as Point)
          );
      }

      return [...merged]
        .filter(station => matchesQuery(station, query))
        .sort(compareStations);
    },
    [stations, draft, isNew, query, searchPoint]
  );


  /** Metres from the searched point, for the rows the rail is showing. */
  const distances = useMemo(
    () => {

      const measured = new Map<string, number>();

      if (!searchPoint) return measured;

      for (const station of visible) {

        const point = stationPoint(station);

        if (point) {
          measured.set(station.id, distanceMetres(searchPoint, point));
        }
      }

      return measured;
    },
    [visible, searchPoint]
  );


  const mapStations = useMemo(
    () =>
      draft && !isNew
        ? stations.map(
            station => (station.id === draft.id ? draft : station)
          )
        : stations,
    [stations, draft, isNew]
  );


  const dirtyIds = useMemo(
    () =>
      new Set(
        isDirty && draft && !isNew ? [draft.id] : []
      ),
    [isDirty, draft, isNew]
  );


  const pendingPosition = useMemo(
    (): [number, number] | null => {

      if (
        !isNew ||
        !draft ||
        typeof draft.latitude !== 'number' ||
        typeof draft.longitude !== 'number' ||
        !Number.isFinite(draft.latitude) ||
        !Number.isFinite(draft.longitude)
      ) {
        return null;
      }

      return [draft.latitude, draft.longitude];
    },
    [isNew, draft]
  );


  // Warn on a tab close with an edit still unsaved: nothing here autosaves.
  useEffect(
    () => {

      if (!isDirty) return;

      const warn = (
        event: BeforeUnloadEvent
      ) => {
        event.preventDefault();
      };

      window.addEventListener('beforeunload', warn);

      return () => window.removeEventListener('beforeunload', warn);
    },
    [isDirty]
  );


  // ============================================================
  // FIRST LOAD
  // ============================================================

  if (isLoading) {

    return (
      <div
        className="
          w-screen
          h-screen
          flex
          flex-col
          items-center
          justify-center
          gap-3
          bg-[#0d0d10]
          text-zinc-300
        "
      >
        <Loader2 className="w-8 h-8 animate-spin text-blue-400" />
        <div>Loading stations…</div>
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

      <header
        className="
          flex
          items-center
          justify-between
          h-14
          px-4
          bg-[#141416]
          border-b
          border-[#232326]
          shrink-0
          select-none
        "
      >

        <div className="flex items-center gap-3">

          <div className="flex items-center gap-2">

            <div
              className="
                flex
                items-center
                justify-center
                w-7
                h-7
                rounded
                bg-[#00bcd4]
                font-bold
                text-black
                text-xs
              "
            >
              FP
            </div>

            <span className="text-xs font-black tracking-widest text-white uppercase hidden lg:inline">
              Station Map
            </span>

          </div>

          <Link
            href="/annotation"
            className="
              flex
              items-center
              gap-1.5
              border-l
              border-zinc-800
              pl-3
              text-xs
              text-zinc-400
              hover:text-zinc-100
              transition-colors
            "
          >
            <ArrowLeft className="w-3.5 h-3.5" />
            Annotation
          </Link>

          <Link
            href="/validation"
            className="text-xs text-zinc-400 hover:text-zinc-100 transition-colors"
          >
            Validation
          </Link>

        </div>

        <div className="flex items-center gap-3">

          {
            status && (
              <span className="text-[11px] text-green-400/90">
                {status}
              </span>
            )
          }

          <span className="text-[11px] text-zinc-500">
            {stations.length} stations
            {etag && ` · etag ${etag.slice(-6)}`}
          </span>

          <button
            onClick={() => void loadStations()}
            title="Reload stations"
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
        {/* LEFT: STATIONS */}
        {/* ================================================= */}

        <aside
          className="
            w-72
            shrink-0
            flex
            flex-col
            h-full
            bg-[#141416]
            border-r
            border-[#232326]
          "
        >

          <div
            className="
              flex
              items-center
              justify-between
              gap-2
              px-4
              h-12
              border-b
              border-[#232326]
              shrink-0
            "
          >

            <span className="text-xs font-semibold uppercase tracking-wider text-zinc-400">
              Stations
            </span>

            <button
              onClick={startNew}
              title="Add a station"
              className="
                flex
                items-center
                gap-1
                rounded
                px-1.5
                py-1
                text-[11px]
                text-zinc-400
                hover:text-zinc-100
                hover:bg-[#202024]
              "
            >
              <Plus className="w-3.5 h-3.5" />
              Add
            </button>

          </div>


          <div className="px-3 py-2 border-b border-[#232326] shrink-0">

            <div className="relative">

              <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-zinc-600" />

              <input
                value={query}
                onChange={event => setQuery(event.target.value)}
                placeholder="Search name, brand, id or coordinates"
                className="
                  w-full
                  rounded
                  border
                  border-[#232326]
                  bg-[#0f0f11]
                  pl-7
                  pr-2
                  py-1.5
                  text-xs
                  text-zinc-200
                  placeholder:text-zinc-600
                  focus:outline-none
                  focus:border-blue-500/60
                "
              />

            </div>

            {/* Says the search changed meaning: the rail is now ordered by
                distance from a place, not filtered by a string. */}
            {
              searchPoint && (

                <p className="mt-1.5 text-[10px] text-blue-400">
                  Nearest to {formatCoordinate(searchPoint.latitude)},{' '}
                  {formatCoordinate(searchPoint.longitude)}
                </p>
              )
            }

          </div>


          <div className="flex-1 min-h-0 overflow-y-auto">

            {
              loadError
                ? (
                  <div className="px-4 py-6 text-xs text-red-300">
                    {loadError}
                  </div>
                )
                : (
                  <StationList
                    stations={visible}
                    selectedId={selectedId}
                    dirtyIds={dirtyIds}
                    distances={distances}
                    onSelect={select}
                  />
                )
            }

          </div>


          {/* No legend: every marker is the same colour, so there is
              nothing for one to decode. Where a station came from is
              named in words in the panel instead. */}

        </aside>


        {/* ================================================= */}
        {/* MIDDLE: MAP */}
        {/* ================================================= */}

        <main className="flex-1 min-w-0 h-full relative">

          <DynamicStationMap
            stations={mapStations}
            selectedId={selectedId}
            isPlacing={isPlacing}
            pendingPosition={pendingPosition}
            isDraggable={!isNew}
            searchPoint={searchPoint}
            triggerRadiusM={triggerRadiusM}
            onSelect={select}
            onMove={moveStation}
            onPlace={placeAt}
          />

          {
            isPlacing && (

              <div
                className="
                  absolute
                  top-3
                  left-1/2
                  -translate-x-1/2
                  z-[1000]
                  flex
                  items-center
                  gap-2
                  rounded-full
                  border
                  border-green-500/40
                  bg-[#141416]/95
                  px-3
                  py-1.5
                  text-[11px]
                  text-green-300
                "
              >
                <MapPin className="w-3.5 h-3.5" />
                Click the map to set the position
              </div>
            )
          }

          {
            triggerRadiusM != null && (

              <div
                className="
                  absolute
                  bottom-3
                  left-3
                  z-[1000]
                  flex
                  items-center
                  gap-2
                  rounded
                  border
                  border-[#232326]
                  bg-[#141416]/95
                  px-2.5
                  py-1.5
                  text-[11px]
                  text-zinc-300
                "
              >
                <span
                  className="
                    inline-block
                    w-3
                    h-3
                    rounded-full
                    border
                  "
                  style={{
                    borderColor: '#38bdf8',
                    background: 'rgba(56,189,248,0.12)'
                  }}
                />
                Capture trigger radius {Math.round(triggerRadiusM)} m
              </div>
            )
          }

        </main>


        {/* ================================================= */}
        {/* RIGHT: THE SELECTED STATION */}
        {/* ================================================= */}

        {
          draft
            ? (

              <StationPanel
                draft={draft}
                isNew={isNew}
                isDirty={isDirty}
                isSaving={isSaving}
                isDeleting={isDeleting}
                isPlacing={isPlacing}
                idConflict={idConflict}
                error={panelError}
                onChange={patchDraft}
                onSave={() => void save()}
                onRevert={revert}
                onDelete={() => void remove()}
                onStartPlacing={() => setIsPlacing(true)}
                onClose={() => select(null)}
              />
            )
            : (

              <aside
                className="
                  w-80
                  shrink-0
                  flex
                  flex-col
                  items-center
                  justify-center
                  gap-2
                  h-full
                  bg-[#141416]
                  border-l
                  border-[#232326]
                  px-6
                  text-center
                "
              >

                <MapPin className="w-6 h-6 text-zinc-700" />

                <p className="text-xs text-zinc-500">
                  Pick a station on the map or in the list to edit it.
                </p>

                <button
                  onClick={startNew}
                  className="mt-1 text-[11px] text-blue-400 hover:text-blue-300"
                >
                  Or add a new one
                </button>

              </aside>
            )
        }

      </div>

    </div>
  );
};
