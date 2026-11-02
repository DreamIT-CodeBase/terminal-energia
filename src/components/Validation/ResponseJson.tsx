'use client';

import React, { useEffect, useState } from 'react';

import { Check, FileJson, HardDriveDownload, Loader2, Trash2 } from 'lucide-react';

import { Station } from '../../types';
import {
  applyStationFuelTypes,
  coordinatesFromResponse,
  EnrichedFuelType,
  EnrichedStation,
  findStationByCoordinates,
  firstBoardWithFuelPriceRowsIndex,
  stationNameFromResponse
} from './shared';


// ============================================================
// RESPONSE JSON
//
// The pipeline's api_response.json for the selected run, rendered
// as a JSON-shaped tree rather than a flat dump so two fields can
// be corrected in place:
//
//   - `fuel_type` and `price` (inside each fuel_prices row) are
//     editable inputs.
//   - everything else is read-only.
//
// Some fields are dropped from the view entirely: `raw_text` on a
// price row, and `ordinal` / `count` / `board_id` / `images_read`
// / `seen_s` on a board. They still exist in the source; they are
// just noise for this check.
// ============================================================

// What a value is standing in for, which decides what gets hidden and what can
// be edited. Set when descending into the `boards` and `fuel_prices` arrays.
type Kind = 'root' | 'board' | 'fuelPrice' | 'other';

const HIDDEN_BOARD_KEYS = new Set([
  'ordinal',
  'count',
  'board_id',
  'images_read',
  'seen_s'
]);

const HIDDEN_FUEL_PRICE_KEYS = new Set(['raw_text']);


const isHidden = (
  key: string,
  kind: Kind
): boolean => {

  if (kind === 'board') return HIDDEN_BOARD_KEYS.has(key);
  if (kind === 'fuelPrice') return HIDDEN_FUEL_PRICE_KEYS.has(key);

  return false;
};


const isEditable = (
  key: string,
  kind: Kind
): boolean =>
  kind === 'fuelPrice' && (key === 'fuel_type' || key === 'price');


// The kind carried into the value under `key`. For an array it is the kind of
// each element; for an object it is that object's own kind.
const childKind = (
  key: string
): Kind => {

  if (key === 'boards') return 'board';
  if (key === 'fuel_prices') return 'fuelPrice';

  return 'other';
};


const hasFuelPriceRows = (
  value: unknown
): boolean => {

  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return false;
  }

  const fuelPrices = (value as Record<string, unknown>).fuel_prices;

  return Array.isArray(fuelPrices) && fuelPrices.length > 0;
};


// ============================================================
// EDIT STATE
//
// Edits are held by path, e.g. `boards.0.fuel_prices.2.price`, so
// a value can be overridden without rebuilding the response, and
// the originals stay untouched underneath.
// ============================================================

type Edits = Record<string, string>;


interface NodeProps {
  value: unknown;
  kind: Kind;
  path: string;
  editable: boolean;
  edits: Edits;
  setEdit: (path: string, value: string) => void;
  // Board paths (`boards.0`) staged for removal, and the toggle for them. A
  // board renders with a remove control; removing it hides it here and drops
  // it from the JSON on submit.
  removed: Record<string, boolean>;
  removeBoard: (path: string) => void;
  removedFuelPrices: Record<string, boolean>;
  removeFuelPrice: (path: string) => void;
}


const EditableLeaf: React.FC<{
  value: unknown;
  path: string;
  edits: Edits;
  setEdit: (path: string, value: string) => void;
}> = ({ value, path, edits, setEdit }) => {

  const isNumber = typeof value === 'number';

  const current =
    edits[path] ??
    (value === null || value === undefined ? '' : String(value));

  return (
    <input
      value={current}
      inputMode={isNumber ? 'decimal' : 'text'}
      onChange={event => setEdit(path, event.target.value)}
      // Grow with the content so the tree stays tight around short values.
      style={{ width: `${Math.max(current.length, 2) + 3}ch` }}
      className="inline-block px-1 py-0 rounded bg-[#0d0d10] border border-[#3a3a42] text-amber-200 focus:border-blue-500 focus:outline-none"
    />
  );
};


const StaticLeaf: React.FC<{ value: unknown }> = ({ value }) => {

  if (value === null || value === undefined) {
    return <span className="text-zinc-500 italic">null</span>;
  }

  if (typeof value === 'string') {
    return <span className="text-emerald-300">&quot;{value}&quot;</span>;
  }

  if (typeof value === 'number') {
    return <span className="text-orange-300">{String(value)}</span>;
  }

  if (typeof value === 'boolean') {
    return <span className="text-amber-300">{String(value)}</span>;
  }

  return <span className="text-zinc-300">{String(value)}</span>;
};


const Node: React.FC<NodeProps> = ({
  value,
  kind,
  path,
  editable,
  edits,
  setEdit,
  removed,
  removeBoard,
  removedFuelPrices,
  removeFuelPrice
}) => {

  // ----- Object -----
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {

    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([key]) => !isHidden(key, kind));

    return (
      <span>
        <span className="text-zinc-600">{'{'}</span>
        <div className="pl-3">
          {
            entries.map(([key, child], index) => (
              <div key={key}>
                <span className="text-sky-300">&quot;{key}&quot;</span>
                <span className="text-zinc-500">: </span>
                <Node
                  value={child}
                  kind={childKind(key)}
                  path={path ? `${path}.${key}` : key}
                  editable={isEditable(key, kind)}
                  edits={edits}
                  setEdit={setEdit}
                  removed={removed}
                  removeBoard={removeBoard}
                  removedFuelPrices={removedFuelPrices}
                  removeFuelPrice={removeFuelPrice}
                />
                {
                  index < entries.length - 1 && (
                    <span className="text-zinc-600">,</span>
                  )
                }
              </div>
            ))
          }
        </div>
        <span className="text-zinc-600">{'}'}</span>
      </span>
    );
  }

  // ----- Array -----
  if (Array.isArray(value)) {

    // Hide boards without rows for display only. Original indices stay in each
    // path so edits and explicit removals still target `base`.
    const items = value
      .map((element, index) => ({ element, path: `${path}.${index}` }))
      .filter(item =>
        (kind !== 'board' || (
          hasFuelPriceRows(item.element) &&
          !removed[item.path]
        )) &&
        (kind !== 'fuelPrice' || !removedFuelPrices[item.path])
      );

    if (items.length === 0) {
      return <span className="text-zinc-600">[]</span>;
    }

    return (
      <span>
        <span className="text-zinc-600">[</span>
        <div className="pl-3">
          {
            items.map((item, visibleIndex) => (
              <div key={item.path} className="group/board flex items-start gap-1">
                {
                  kind === 'board' && (
                    <button
                      onClick={() => removeBoard(item.path)}
                      title="Remove this board"
                      className="mt-0.5 shrink-0 p-0.5 rounded text-zinc-600 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                    >
                      <Trash2 className="w-3 h-3" />
                    </button>
                  )
                }
                {
                  kind === 'fuelPrice' && (
                    <button
                      onClick={() => removeFuelPrice(item.path)}
                      title="Remove this price row"
                      className="mt-0.5 shrink-0 p-0.5 rounded text-zinc-600 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                    >
                      <Trash2 className="w-3 h-3" />
                    </button>
                  )
                }
                <span className="min-w-0">
                  <Node
                    value={item.element}
                    // Elements inherit the kind carried into the array.
                    kind={kind}
                    path={item.path}
                    editable={false}
                    edits={edits}
                    setEdit={setEdit}
                    removed={removed}
                    removeBoard={removeBoard}
                    removedFuelPrices={removedFuelPrices}
                    removeFuelPrice={removeFuelPrice}
                  />
                  {
                    visibleIndex < items.length - 1 && (
                      <span className="text-zinc-600">,</span>
                    )
                  }
                </span>
              </div>
            ))
          }
        </div>
        <span className="text-zinc-600">]</span>
      </span>
    );
  }

  // ----- Leaf -----
  if (editable) {
    return (
      <EditableLeaf
        value={value}
        path={path}
        edits={edits}
        setEdit={setEdit}
      />
    );
  }

  return <StaticLeaf value={value} />;
};


// ============================================================
// LOCAL DRAFT
//
// Submitting writes the edited response to localStorage, keyed by
// run, and the panel reads it back on the next visit. This is a
// scratch copy on the reviewer's machine only -- never the blob,
// and gone when the browser data is cleared.
// ============================================================

const localKey = (videoId: string) =>
  `fp-validation:response:${videoId}`;


const valueAtPath = (
  root: unknown,
  path: string
): unknown =>
  path
    .split('.')
    .reduce<unknown>(
      (node, segment) =>
        node && typeof node === 'object'
          ? (node as Record<string, unknown>)[segment]
          : undefined,
      root
    );


// Writes into a deep clone the caller already owns, so the original response is
// never mutated in place.
const setValueAtPath = (
  root: Record<string, unknown>,
  path: string,
  value: unknown
): void => {

  const segments = path.split('.');

  let node: Record<string, unknown> = root;

  for (let i = 0; i < segments.length - 1; i += 1) {
    node = node[segments[i]] as Record<string, unknown>;
  }

  node[segments[segments.length - 1]] = value;
};


const asDisplayString = (
  value: unknown
): string =>
  value === null || value === undefined ? '' : String(value);


interface ResponseJsonProps {
  apiResponse: unknown;
  // Keys the local draft, so each run keeps its own edits.
  videoId: string | null;
  isLoading: boolean;
  hasSelection: boolean;
  stations: EnrichedStation[];
  stationMapping: Station[];
  onEnrichedStationSaved: (station: EnrichedStation) => void;
}


export const ResponseJson: React.FC<ResponseJsonProps> = ({
  apiResponse,
  videoId,
  isLoading,
  hasSelection,
  stations,
  stationMapping,
  onEnrichedStationSaved
}) => {

  // The JSON actually shown and edited. It starts from the local draft when one
  // exists for this run, otherwise from the response the server returned.
  const [base, setBase] = useState<unknown>(null);

  // Unsaved edits, by path. `submit` folds them into `base` and clears them.
  const [edits, setEdits] = useState<Edits>({});

  // Board paths (`boards.0`) staged for removal, cleared on submit.
  const [removed, setRemoved] = useState<Record<string, boolean>>({});
  const [removedFuelPrices, setRemovedFuelPrices] =
    useState<Record<string, boolean>>({});

  // Brief confirmation after a submit.
  const [justSaved, setJustSaved] = useState(false);
  const [enrichedSaved, setEnrichedSaved] = useState(false);
  const [enrichedSaveError, setEnrichedSaveError] = useState('');
  const [responseSaveError, setResponseSaveError] = useState('');
  const [isSavingResponse, setIsSavingResponse] = useState(false);
  const [pendingResponseSave, setPendingResponseSave] = useState<unknown>(null);
  const [pendingEnrichedUpdate, setPendingEnrichedUpdate] = useState<{
    latitude: number;
    longitude: number;
    name: string | null;
    fuel_types: EnrichedFuelType[];
    deleted_row_nums: number[];
  } | null>(null);


  // A new run: prefer the cloud response, fall back to a browser draft, and
  // drop any edits from the run before.
  useEffect(
    () => {

      let draft: unknown = null;

      try {
        const raw = videoId ? window.localStorage.getItem(localKey(videoId)) : null;
        if (raw) draft = JSON.parse(raw);
      } catch {
        // A private window or cleared storage just means no draft.
      }

      // The API response may be the cloud-saved correction from another
      // reviewer; it takes precedence over this browser's older draft.
      const source = apiResponse ?? draft;
      const matchedStation = findStationByCoordinates(source, stations);
      const hydratedResponse = matchedStation
        ? applyStationFuelTypes(source, matchedStation.fuel_types)
        : source;

      // The panel state must reset when the selected run or its response
      // changes; this is the existing response-loading lifecycle.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setBase(hydratedResponse ?? null);
      setEdits({});
      setRemoved({});
      setRemovedFuelPrices({});
      setJustSaved(false);
      setEnrichedSaved(false);
      setEnrichedSaveError('');
      setResponseSaveError('');
      setPendingResponseSave(null);
    },
    // Enriched station refreshes are handled below without resetting edits.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [apiResponse, videoId]
  );

  useEffect(
    () => {
      // Synchronize already-loaded response data when the station list refreshes.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setBase((current: unknown) => {
        if (current === null) return current;

        const matchedStation = findStationByCoordinates(current, stations);
        return matchedStation
          ? applyStationFuelTypes(current, matchedStation.fuel_types)
          : current;
      });
    },
    // Refresh fuel names when enriched station data changes without resetting
    // other response edits made in this panel.
    [stations]
  );


  const setEdit = (path: string, value: string) => {
    setEdits(prev => ({ ...prev, [path]: value }));
    setJustSaved(false);
    setEnrichedSaved(false);
  };


  const removeBoard = (path: string) => {
    setRemoved(prev => ({ ...prev, [path]: true }));
    setJustSaved(false);
    setEnrichedSaved(false);
  };

  const removeFuelPrice = (path: string) => {
    setRemovedFuelPrices(prev => ({ ...prev, [path]: true }));
    setJustSaved(false);
    setEnrichedSaved(false);
  };


  // Only edits that actually differ from what is shown count as changes, so
  // typing a value back to its original leaves nothing to submit.
  const changedPaths = Object.keys(edits).filter(
    path => edits[path] !== asDisplayString(valueAtPath(base, path))
  );

  const removedBoards = Object.keys(removed).filter(path => removed[path]);
  const removedFuelPricePaths = Object.keys(removedFuelPrices)
    .filter(path => removedFuelPrices[path]);

  const changeCount =
    changedPaths.length + removedBoards.length + removedFuelPricePaths.length;

  const dirty = changeCount > 0;

  const saveEnrichedUpdate = async (update: {
    latitude: number;
    longitude: number;
    name: string | null;
    fuel_types: EnrichedFuelType[];
    deleted_row_nums: number[];
  }) => {
    setEnrichedSaveError('');

    try {
      const response = await fetch('/api/stations-enriched', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(update)
      });

      if (!response.ok) {
        throw new Error(`Enriched station update failed with status ${response.status}.`);
      }

      const data: { station?: EnrichedStation } = await response.json();
      if (!data.station) {
        throw new Error('Enriched station update returned no station record.');
      }

      setPendingEnrichedUpdate(null);
      setEnrichedSaved(true);
      onEnrichedStationSaved(data.station);
    } catch (error) {
      console.error('Enriched station save error:', error);
      setPendingEnrichedUpdate(update);
      setEnrichedSaveError(
        error instanceof Error
          ? error.message
          : 'Could not save enriched station fuel types.'
      );
    }
  };

  const saveUpdatedResponse = async (responseJson: unknown) => {
    setIsSavingResponse(true);
    setResponseSaveError('');

    try {
      const response = await fetch(
        `/api/videos/${encodeURIComponent(videoId ?? '')}/api-response`,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(responseJson)
        }
      );

      if (!response.ok) {
        throw new Error(`Updated response save failed with status ${response.status}.`);
      }

      setPendingResponseSave(null);
      setResponseSaveError('');
    } catch (error) {
      console.error('Updated response save error:', error);
      setPendingResponseSave(responseJson);
      setResponseSaveError(
        error instanceof Error
          ? error.message
          : 'Could not save the updated response to cloud storage.'
      );
    } finally {
      setIsSavingResponse(false);
    }
  };


  const submit = async () => {

    if (!videoId || base == null || !dirty) return;

    setEnrichedSaved(false);
    setEnrichedSaveError('');

    const next = JSON.parse(JSON.stringify(base)) as Record<string, unknown>;

    for (const path of changedPaths) {

      const original = valueAtPath(base, path);
      const raw = edits[path];

      // A price is a number in the schema, so keep it one where the edit is
      // numeric; anything else (including fuel_type) is stored as typed.
      let value: unknown = raw;

      if (typeof original === 'number') {
        const parsed = Number(raw);
        value = raw.trim() !== '' && !Number.isNaN(parsed) ? parsed : raw;
      }

      setValueAtPath(next, path, value);
    }

    const firstBoardIndex = firstBoardWithFuelPriceRowsIndex(base);
    const firstBoardPrefix = `boards.${firstBoardIndex}.fuel_prices.`;
    const firstBoardDeleted = removedBoards.includes(`boards.${firstBoardIndex}`);
    const changedFirstBoardFuelType = changedPaths.some(
      path => path.startsWith(firstBoardPrefix) && path.endsWith('.fuel_type')
    );
    const deletedFirstBoardRows = removedFuelPricePaths.filter(
      path => path.startsWith(firstBoardPrefix)
    );
    const shouldSaveFuelTypes =
      firstBoardIndex >= 0 &&
      !firstBoardDeleted &&
      (changedFirstBoardFuelType || deletedFirstBoardRows.length > 0);

    if (shouldSaveFuelTypes) {
      const matchedResponseStation = findStationByCoordinates(next, stationMapping);
      const firstBoard = Array.isArray(next.boards)
        ? next.boards[firstBoardIndex]
        : null;
      const rows = firstBoard &&
        typeof firstBoard === 'object' &&
        Array.isArray((firstBoard as Record<string, unknown>).fuel_prices)
        ? (firstBoard as Record<string, unknown>).fuel_prices as unknown[]
        : [];
      const fuelTypes: EnrichedFuelType[] = rows.flatMap((row, index) => {
        if (removedFuelPrices[
          `boards.${firstBoardIndex}.fuel_prices.${index}`
        ]) {
          return [];
        }

        const rowObject =
          row !== null && typeof row === 'object'
            ? row as Record<string, unknown>
            : {};

        return [{
          row_num:
            typeof rowObject.row_num === 'number'
              ? rowObject.row_num
              : index + 1,
          fuel_type: String(rowObject.fuel_type ?? '').trim()
        }];
      });
      const deletedRowNums = deletedFirstBoardRows.map(path => {
        const rowIndex = Number(path.slice(path.lastIndexOf('.') + 1));
        const sourceBoards = (base as Record<string, unknown>).boards;
        const sourceBoard = Array.isArray(sourceBoards)
          ? sourceBoards[firstBoardIndex]
          : null;
        const sourceRows = sourceBoard !== null && typeof sourceBoard === 'object'
          ? (sourceBoard as Record<string, unknown>).fuel_prices
          : null;
        const sourceRow = Array.isArray(sourceRows)
          ? sourceRows[rowIndex]
          : null;
        const sourceRowObject =
          sourceRow !== null && typeof sourceRow === 'object'
            ? sourceRow as Record<string, unknown>
            : {};

        return typeof sourceRowObject.row_num === 'number'
          ? sourceRowObject.row_num
          : rowIndex + 1;
      });
      const coordinates = coordinatesFromResponse(next);

      if (coordinates && (fuelTypes.length > 0 || deletedRowNums.length > 0)) {
        const update = {
          latitude: coordinates.latitude,
          longitude: coordinates.longitude,
          name: matchedResponseStation?.name ??
            stationNameFromResponse(next),
          fuel_types: fuelTypes,
          deleted_row_nums: deletedRowNums
        };
        setPendingEnrichedUpdate(update);
        await saveEnrichedUpdate(update);
      } else {
        setEnrichedSaveError(
          'Fuel types changed, but the response has no numeric station coordinates.'
        );
      }
    }

    if (Array.isArray(next.boards)) {
      next.boards.forEach((board, boardIndex) => {
        if (board === null || typeof board !== 'object') return;
        const boardObject = board as Record<string, unknown>;
        if (!Array.isArray(boardObject.fuel_prices)) return;

        boardObject.fuel_prices = (boardObject.fuel_prices as unknown[])
          .filter((_, rowIndex) =>
            !removedFuelPrices[
              `boards.${boardIndex}.fuel_prices.${rowIndex}`
            ]
          );
      });
    }

    // Drop removed boards only after the first board's coordinate mapping has
    // been built, then keep board_count honest with what remains.
    if (Array.isArray(next.boards)) {
      const remainingBoards = (next.boards as unknown[]).filter(
        (_, index) => !removed[`boards.${index}`]
      );
      next.boards = remainingBoards;

      if (typeof next.board_count === 'number') {
        next.board_count = remainingBoards.length;
      }
    }

    await saveUpdatedResponse(next);

    try {
      window.localStorage.setItem(localKey(videoId), JSON.stringify(next));
    } catch {
      // Storage can be full or blocked; the in-memory update below still holds
      // for this session.
    }

    setBase(next);
    setEdits({});
    setRemoved({});
    setRemovedFuelPrices({});
    setJustSaved(true);
  };


  const hasResponse = base != null;

  return (

    <aside className="w-96 shrink-0 flex flex-col h-full bg-[#141416] border-l border-[#232326] select-none text-zinc-300">

      <div className="flex items-center gap-2 px-4 h-12 border-b border-[#232326] shrink-0">
        <FileJson className="w-4 h-4 text-blue-500" />
        <span className="text-xs font-semibold uppercase tracking-wider text-zinc-400">
          Response JSON
        </span>
      </div>

      <div className="flex-1 min-h-0 overflow-auto">

        {
          isLoading
            ? (
              <div className="flex flex-col items-center justify-center gap-2 h-full text-zinc-500">
                <Loader2 className="w-5 h-5 animate-spin text-blue-400" />
                <span className="text-xs">Loading response…</span>
              </div>
            )
            : hasResponse
              ? (
                <div className="p-4 font-mono text-[11px] leading-relaxed">
                  <Node
                    value={base}
                    kind="root"
                    path=""
                    editable={false}
                    edits={edits}
                    setEdit={setEdit}
                    removed={removed}
                    removeBoard={removeBoard}
                    removedFuelPrices={removedFuelPrices}
                    removeFuelPrice={removeFuelPrice}
                  />
                </div>
              )
              : (
                <div className="flex items-center justify-center h-full px-6 text-center text-[11px] text-zinc-600">
                  {
                    hasSelection
                      ? 'No response.json for this run.'
                      : 'Pick a run on the left to see its response.'
                  }
                </div>
              )
        }

      </div>


      {/* Submit persists the corrected response to cloud storage and local draft storage. */}

      {
        hasResponse && (
          <div className="flex items-center gap-2 px-3 h-14 border-t border-[#232326] shrink-0">

            <span className="text-[11px] text-zinc-500 min-w-0 truncate">
              {
                dirty
                  ? `${changeCount} unsaved ${changeCount === 1 ? 'change' : 'changes'}`
                  : responseSaveError
                    ? responseSaveError
                  : enrichedSaveError
                    ? enrichedSaveError
                    : enrichedSaved
                    ? (
                      <span className="flex items-center gap-1 text-green-400">
                        <Check className="w-3.5 h-3.5" />
                        Response saved to cloud; fuel names saved to enriched stations
                      </span>
                    )
                    : justSaved
                    ? (
                      <span className="flex items-center gap-1 text-green-400">
                        <Check className="w-3.5 h-3.5" />
                        Saved to cloud and local storage
                      </span>
                    )
                    : 'No changes'
              }
            </span>

            {
              pendingResponseSave !== null && (
                <button
                  onClick={() => saveUpdatedResponse(pendingResponseSave)}
                  disabled={isSavingResponse}
                  title="Retry saving the updated response to cloud storage"
                  className="px-2 py-2 rounded border border-amber-500/40 text-[11px] font-semibold text-amber-300 hover:bg-amber-500/10 disabled:opacity-50"
                >
                  Retry
                </button>
              )
            }

            {
              pendingEnrichedUpdate && (
                <button
                  onClick={() => saveEnrichedUpdate(pendingEnrichedUpdate)}
                  title="Retry saving fuel names to enriched stations"
                  className="px-2 py-2 rounded border border-amber-500/40 text-[11px] font-semibold text-amber-300 hover:bg-amber-500/10"
                >
                  Retry
                </button>
              )
            }

            <button
              onClick={submit}
              disabled={!dirty || isSavingResponse}
              title={
                dirty
                  ? 'Save response edits locally and fuel-name corrections to enriched stations'
                  : 'Edit a price or fuel type, or remove a board, to enable submit'
              }
              className="ml-auto flex items-center gap-2 px-4 py-2 rounded bg-blue-600 hover:bg-blue-500 disabled:opacity-40 disabled:hover:bg-blue-600 text-sm font-semibold text-white transition-colors"
            >
              <HardDriveDownload className="w-4 h-4" />
              Submit
            </button>

          </div>
        )
      }

    </aside>
  );
};
