'use client';

import React, { useState } from 'react';

import {
  Crosshair,
  Loader2,
  Save,
  Trash2,
  Undo2,
  X
} from 'lucide-react';

import { Station } from '../../types';

import { FuelTypeEditor } from './FuelTypeEditor';

import {
  formatModified,
  hasPosition,
  PROVENANCE_LABEL,
  provenanceOf
} from './shared';


interface StationPanelProps {
  // The station as edited. For a new one this is the draft being built.
  draft: Station;
  isNew: boolean;
  isDirty: boolean;
  isSaving: boolean;
  isDeleting: boolean;
  // Set while the map is waiting for a click to position a new station.
  isPlacing: boolean;
  // Reported per field so the id can be shown as taken before a save turns
  // an add into an accidental overwrite.
  idConflict: boolean;
  error: string;
  onChange: (patch: Partial<Station>) => void;
  onSave: () => void;
  onRevert: () => void;
  onDelete: () => void;
  onStartPlacing: () => void;
  onClose: () => void;
}


const FIELD_CLASS = `
  w-full
  rounded
  border
  border-[#232326]
  bg-[#0f0f11]
  px-2
  py-1.5
  text-xs
  text-zinc-200
  placeholder:text-zinc-600
  focus:outline-none
  focus:border-blue-500/60
`;

const LABEL_CLASS = `
  block
  mb-1
  text-[11px]
  uppercase
  tracking-wider
  text-zinc-500
`;


/**
 * Coordinates are edited as text, not as numbers, so a half-typed value like
 * `58.` survives the keystroke instead of being parsed to something else and
 * written back over what is being typed.
 */
const coordinateText = (
  value: number | null
): string =>
  typeof value === 'number' && Number.isFinite(value)
    ? String(value)
    : '';


// ============================================================
// STATION PANEL
//
// Everything editable about one station, plus the delete. Writes
// merge server-side, so only the fields shown here are ever sent
// and anything the document carries that this does not know about
// survives untouched.
// ============================================================

export const StationPanel: React.FC<StationPanelProps> = ({
  draft,
  isNew,
  isDirty,
  isSaving,
  isDeleting,
  isPlacing,
  idConflict,
  error,
  onChange,
  onSave,
  onRevert,
  onDelete,
  onStartPlacing,
  onClose
}) => {

  // Typed out in full before a delete goes through: the API returns the only
  // remaining copy of the entry, so there is nothing to undo it with.
  const [deleteConfirmation, setDeleteConfirmation] = useState('');
  const [isConfirmingDelete, setIsConfirmingDelete] = useState(false);

  const deleteTarget = draft.name?.trim() || draft.id;

  const canDelete = deleteConfirmation.trim() === deleteTarget;

  const placed = hasPosition(draft);

  const canSave =
    isDirty &&
    !isSaving &&
    !idConflict &&
    Boolean(draft.id) &&
    // A new station without a position would be invisible on the map it was
    // added from. An existing one can be saved without touching coordinates.
    (!isNew || placed);

  return (

    <aside
      className="
        w-80
        shrink-0
        flex
        flex-col
        h-full
        bg-[#141416]
        border-l
        border-[#232326]
      "
    >

      {/* ================================================= */}
      {/* HEADER */}
      {/* ================================================= */}

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
          {isNew ? 'New station' : 'Station'}
        </span>

        <button
          onClick={onClose}
          title="Close"
          className="p-1 rounded text-zinc-500 hover:text-zinc-200 hover:bg-[#202024]"
        >
          <X className="w-4 h-4" />
        </button>

      </div>


      <div className="flex-1 min-h-0 overflow-y-auto p-4 space-y-4">

        {/* =============================================== */}
        {/* ID                                              */}
        {/*                                                 */}
        {/* The key. For a new station it follows the name,  */}
        {/* so it is shown rather than edited -- saving onto */}
        {/* an id that exists would amend that station.      */}
        {/* =============================================== */}

        <div>

          <label className={LABEL_CLASS}>
            Id
          </label>

          <div
            className="
              rounded
              border
              border-[#232326]
              bg-[#0a0a0c]
              px-2
              py-1.5
              text-xs
              font-mono
              text-zinc-400
              break-all
            "
          >
            {draft.id || '—'}
          </div>

          {
            idConflict
              ? (
                <p className="mt-1 text-[11px] text-red-400">
                  A station with this id already exists. Change the name.
                </p>
              )
              : (
                <p className="mt-1 text-[10px] text-zinc-600">
                  {
                    isNew
                      ? 'Derived from the name. Entered by hand, so it is surveyed.'
                      : PROVENANCE_LABEL[provenanceOf(draft.id)]
                  }
                </p>
              )
          }

        </div>


        {/* =============================================== */}
        {/* NAME AND BRAND */}
        {/* =============================================== */}

        <div>

          <label className={LABEL_CLASS} htmlFor="station-name">
            Name
          </label>

          <input
            id="station-name"
            value={draft.name ?? ''}
            onChange={event => onChange({ name: event.target.value })}
            placeholder="Tartu Ringtee Neste"
            className={FIELD_CLASS}
          />

        </div>


        <div>

          <label className={LABEL_CLASS} htmlFor="station-brand">
            Brand
          </label>

          <input
            id="station-brand"
            value={draft.brand ?? ''}
            onChange={event => onChange({ brand: event.target.value })}
            placeholder="Neste"
            className={FIELD_CLASS}
          />

        </div>


        {/* =============================================== */}
        {/* POSITION */}
        {/* =============================================== */}

        <div className="space-y-2">

          <div className="flex items-baseline justify-between">

            <span className={LABEL_CLASS}>
              Position
            </span>

            <button
              onClick={onStartPlacing}
              className={`
                text-[10px]
                flex
                items-center
                gap-1
                ${
                  isPlacing
                    ? 'text-green-400'
                    : 'text-zinc-500 hover:text-zinc-300'
                }
              `}
            >
              <Crosshair className="w-3 h-3" />
              {isPlacing ? 'Click the map' : 'Pick on map'}
            </button>

          </div>

          <div className="grid grid-cols-2 gap-2">

            <input
              value={coordinateText(draft.latitude)}
              onChange={event =>
                onChange({
                  latitude:
                    event.target.value.trim() === ''
                      ? null
                      : Number(event.target.value)
                })
              }
              inputMode="decimal"
              placeholder="Latitude"
              className={FIELD_CLASS}
            />

            <input
              value={coordinateText(draft.longitude)}
              onChange={event =>
                onChange({
                  longitude:
                    event.target.value.trim() === ''
                      ? null
                      : Number(event.target.value)
                })
              }
              inputMode="decimal"
              placeholder="Longitude"
              className={FIELD_CLASS}
            />

          </div>

          <p className="text-[10px] text-zinc-600">
            {
              placed
                ? 'Drag the selected marker to move it.'
                : 'Not on the map yet.'
            }
          </p>

        </div>


        {/* =============================================== */}
        {/* BOARD ROWS */}
        {/* =============================================== */}

        <FuelTypeEditor
          value={draft.fuel_types}
          onChange={fuelTypes => onChange({ fuel_types: fuelTypes })}
        />


        {/* =============================================== */}
        {/* NOTE */}
        {/* =============================================== */}

        <div>

          <label className={LABEL_CLASS} htmlFor="station-note">
            Note
          </label>

          <textarea
            id="station-note"
            value={draft.note ?? ''}
            onChange={event => onChange({ note: event.target.value })}
            rows={3}
            placeholder="Anything worth knowing about this forecourt."
            className={`${FIELD_CLASS} resize-none`}
          />

        </div>


        {
          !isNew && (

            <div className="text-[10px] text-zinc-600">
              Last written: {formatModified(draft.modified_time)}
            </div>
          )
        }


        {
          error && (

            <div
              className="
                rounded
                border
                border-red-500/40
                bg-red-500/10
                px-3
                py-2
                text-[11px]
                text-red-300
              "
            >
              {error}
            </div>
          )
        }


        {/* =============================================== */}
        {/* DELETE                                          */}
        {/*                                                 */}
        {/* The response carries the only remaining copy of  */}
        {/* the entry, so the name is typed out first.       */}
        {/* =============================================== */}

        {
          !isNew && (

            <div className="pt-2 border-t border-[#232326] space-y-2">

              {
                !isConfirmingDelete
                  ? (

                    <button
                      onClick={() => setIsConfirmingDelete(true)}
                      className="
                        flex
                        items-center
                        gap-1.5
                        text-[11px]
                        text-zinc-500
                        hover:text-red-400
                      "
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                      Remove this station
                    </button>
                  )
                  : (

                    <div className="space-y-2">

                      <p className="text-[11px] text-zinc-400">
                        This cannot be undone. Type
                        {' '}
                        <span className="text-zinc-200 font-medium">
                          {deleteTarget}
                        </span>
                        {' '}
                        to confirm.
                      </p>

                      <input
                        value={deleteConfirmation}
                        onChange={event =>
                          setDeleteConfirmation(event.target.value)
                        }
                        placeholder={deleteTarget}
                        className={FIELD_CLASS}
                      />

                      <div className="flex items-center gap-2">

                        <button
                          onClick={onDelete}
                          disabled={!canDelete || isDeleting}
                          className="
                            flex
                            items-center
                            gap-1.5
                            rounded
                            bg-red-600
                            px-2.5
                            py-1.5
                            text-[11px]
                            font-medium
                            text-white
                            hover:bg-red-500
                            disabled:opacity-40
                            disabled:hover:bg-red-600
                          "
                        >
                          {
                            isDeleting
                              ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                              : <Trash2 className="w-3.5 h-3.5" />
                          }
                          Delete
                        </button>

                        <button
                          onClick={() => {
                            setIsConfirmingDelete(false);
                            setDeleteConfirmation('');
                          }}
                          className="text-[11px] text-zinc-500 hover:text-zinc-300"
                        >
                          Cancel
                        </button>

                      </div>

                    </div>
                  )
              }

            </div>
          )
        }

      </div>


      {/* ================================================= */}
      {/* SAVE */}
      {/* ================================================= */}

      <div
        className="
          flex
          items-center
          gap-2
          px-4
          h-14
          border-t
          border-[#232326]
          shrink-0
        "
      >

        <button
          onClick={onSave}
          disabled={!canSave}
          className="
            flex
            flex-1
            items-center
            justify-center
            gap-1.5
            rounded
            bg-blue-600
            px-3
            py-2
            text-xs
            font-medium
            text-white
            hover:bg-blue-500
            disabled:opacity-40
            disabled:hover:bg-blue-600
          "
        >
          {
            isSaving
              ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
              : <Save className="w-3.5 h-3.5" />
          }
          {isNew ? 'Add station' : 'Save changes'}
        </button>

        <button
          onClick={onRevert}
          disabled={!isDirty || isSaving}
          title={isNew ? 'Discard' : 'Revert to what is stored'}
          className="
            p-2
            rounded
            text-zinc-500
            hover:text-zinc-200
            hover:bg-[#202024]
            disabled:opacity-30
            disabled:hover:bg-transparent
          "
        >
          <Undo2 className="w-4 h-4" />
        </button>

      </div>

    </aside>
  );
};
