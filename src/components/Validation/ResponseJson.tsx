'use client';

import React, { useEffect, useState } from 'react';

import { Check, FileJson, HardDriveDownload, Loader2 } from 'lucide-react';


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
      style={{ width: `${Math.max(current.length, 2) + 1.5}ch` }}
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
  setEdit
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

    if (value.length === 0) {
      return <span className="text-zinc-600">[]</span>;
    }

    return (
      <span>
        <span className="text-zinc-600">[</span>
        <div className="pl-3">
          {
            value.map((element, index) => (
              <div key={index}>
                <Node
                  value={element}
                  // Elements inherit the kind carried into the array.
                  kind={kind}
                  path={`${path}.${index}`}
                  editable={false}
                  edits={edits}
                  setEdit={setEdit}
                />
                {
                  index < value.length - 1 && (
                    <span className="text-zinc-600">,</span>
                  )
                }
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
}


export const ResponseJson: React.FC<ResponseJsonProps> = ({
  apiResponse,
  videoId,
  isLoading,
  hasSelection
}) => {

  // The JSON actually shown and edited. It starts from the local draft when one
  // exists for this run, otherwise from the response the server returned.
  const [base, setBase] = useState<unknown>(null);

  // Unsaved edits, by path. `submit` folds them into `base` and clears them.
  const [edits, setEdits] = useState<Edits>({});

  // Whether `base` came from a saved draft rather than the server response.
  const [fromDraft, setFromDraft] = useState(false);

  // Brief confirmation after a submit.
  const [justSaved, setJustSaved] = useState(false);


  // A new run: prefer its saved draft, fall back to the server response, and
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

      // eslint-disable-next-line react-hooks/set-state-in-effect
      setBase(draft ?? apiResponse ?? null);
      setEdits({});
      setFromDraft(draft != null);
      setJustSaved(false);
    },
    [apiResponse, videoId]
  );


  const setEdit = (path: string, value: string) => {
    setEdits(prev => ({ ...prev, [path]: value }));
    setJustSaved(false);
  };


  // Only edits that actually differ from what is shown count as changes, so
  // typing a value back to its original leaves nothing to submit.
  const changedPaths = Object.keys(edits).filter(
    path => edits[path] !== asDisplayString(valueAtPath(base, path))
  );

  const dirty = changedPaths.length > 0;


  const submit = () => {

    if (!videoId || base == null || !dirty) return;

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

    try {
      window.localStorage.setItem(localKey(videoId), JSON.stringify(next));
    } catch {
      // Storage can be full or blocked; the in-memory update below still holds
      // for this session.
    }

    setBase(next);
    setEdits({});
    setFromDraft(true);
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
        {
          fromDraft && (
            <span
              title="Showing your locally saved edits for this run, not the original response."
              className="ml-auto px-2 py-0.5 rounded border border-amber-500/40 bg-amber-500/10 text-[10px] font-semibold uppercase tracking-wider text-amber-300"
            >
              Local edit
            </span>
          )
        }
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


      {/* SUBMIT: writes the edited JSON to this browser's local storage */}

      {
        hasResponse && (
          <div className="flex items-center gap-3 px-4 h-14 border-t border-[#232326] shrink-0">

            <span className="text-[11px] text-zinc-500 min-w-0 truncate">
              {
                dirty
                  ? `${changedPaths.length} unsaved ${changedPaths.length === 1 ? 'change' : 'changes'}`
                  : justSaved
                    ? (
                      <span className="flex items-center gap-1 text-green-400">
                        <Check className="w-3.5 h-3.5" />
                        Saved to local storage
                      </span>
                    )
                    : 'No changes'
              }
            </span>

            <button
              onClick={submit}
              disabled={!dirty}
              title={
                dirty
                  ? 'Save these edits to this browser (local storage only)'
                  : 'Edit a price or fuel type to enable submit'
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
