'use client';

import React, { useState } from 'react';

import {
  ChevronDown,
  ChevronUp,
  Plus,
  X
} from 'lucide-react';

import {
  COMMON_FUEL_TYPES,
  moveItem
} from './shared';


interface FuelTypeEditorProps {
  // Null means the sequence is not known, which is different from a board
  // with no rows. The two are kept apart: only an explicit sequence is sent.
  value: string[] | null;
  onChange: (value: string[] | null) => void;
}


// ============================================================
// FUEL TYPE SEQUENCE
//
// The order is the board's row order, top to bottom, and the
// position of a code is its row number -- that is what resolves
// the fuel the detector cannot read off a row. It is data, not
// presentation, so nothing here sorts it: Räpina Olerex really
// does list 98 above 95.
// ============================================================

export const FuelTypeEditor: React.FC<FuelTypeEditorProps> = ({
  value,
  onChange
}) => {

  const [draft, setDraft] = useState('');

  const rows = value ?? [];

  const add = (
    code: string
  ) => {

    const trimmed = code.trim().toUpperCase();

    if (!trimmed) return;

    onChange([...rows, trimmed]);

    setDraft('');
  };

  const removeAt = (
    index: number
  ) => {

    const next = rows.filter((_, position) => position !== index);

    // Back to "not known" rather than "a board with no rows": an empty list
    // would claim the board was surveyed and found bare.
    onChange(next.length ? next : null);
  };

  return (

    <div className="space-y-2">

      <div className="flex items-baseline justify-between">

        <label className="text-[11px] uppercase tracking-wider text-zinc-500">
          Board rows
        </label>

        <span className="text-[10px] text-zinc-600">
          {
            value === null
              ? 'Not surveyed'
              : `${rows.length} row${rows.length === 1 ? '' : 's'}, top first`
          }
        </span>

      </div>


      {
        rows.length > 0 && (

          <ul className="space-y-1">

            {
              rows.map((code, index) => (

                <li
                  key={`${code}-${index}`}
                  className="
                    flex
                    items-center
                    gap-2
                    rounded
                    border
                    border-[#232326]
                    bg-[#0f0f11]
                    px-2
                    py-1
                  "
                >

                  <span className="w-5 text-[10px] text-zinc-600 tabular-nums">
                    {index + 1}
                  </span>

                  <span className="flex-1 text-xs text-zinc-200">
                    {code}
                  </span>

                  <button
                    onClick={() => onChange(moveItem(rows, index, index - 1))}
                    disabled={index === 0}
                    title="Move up"
                    className="
                      p-0.5
                      rounded
                      text-zinc-500
                      hover:text-zinc-200
                      hover:bg-[#202024]
                      disabled:opacity-30
                      disabled:hover:bg-transparent
                    "
                  >
                    <ChevronUp className="w-3.5 h-3.5" />
                  </button>

                  <button
                    onClick={() => onChange(moveItem(rows, index, index + 1))}
                    disabled={index === rows.length - 1}
                    title="Move down"
                    className="
                      p-0.5
                      rounded
                      text-zinc-500
                      hover:text-zinc-200
                      hover:bg-[#202024]
                      disabled:opacity-30
                      disabled:hover:bg-transparent
                    "
                  >
                    <ChevronDown className="w-3.5 h-3.5" />
                  </button>

                  <button
                    onClick={() => removeAt(index)}
                    title="Remove row"
                    className="
                      p-0.5
                      rounded
                      text-zinc-600
                      hover:text-red-400
                      hover:bg-[#202024]
                    "
                  >
                    <X className="w-3.5 h-3.5" />
                  </button>

                </li>
              ))
            }

          </ul>
        )
      }


      <div className="flex items-center gap-1.5">

        <input
          value={draft}
          onChange={event => setDraft(event.target.value)}
          onKeyDown={event => {

            if (event.key === 'Enter') {
              event.preventDefault();
              add(draft);
            }
          }}
          placeholder="Add a row"
          className="
            flex-1
            min-w-0
            rounded
            border
            border-[#232326]
            bg-[#0f0f11]
            px-2
            py-1
            text-xs
            text-zinc-200
            placeholder:text-zinc-600
            focus:outline-none
            focus:border-blue-500/60
          "
        />

        <button
          onClick={() => add(draft)}
          disabled={!draft.trim()}
          title="Add row"
          className="
            p-1
            rounded
            text-zinc-400
            hover:text-zinc-100
            hover:bg-[#202024]
            disabled:opacity-30
            disabled:hover:bg-transparent
          "
        >
          <Plus className="w-3.5 h-3.5" />
        </button>

      </div>


      <div className="flex flex-wrap gap-1">

        {
          COMMON_FUEL_TYPES.map(code => (

            <button
              key={code}
              onClick={() => add(code)}
              className="
                rounded
                border
                border-[#232326]
                px-1.5
                py-0.5
                text-[10px]
                text-zinc-500
                hover:text-zinc-200
                hover:border-zinc-600
              "
            >
              + {code}
            </button>
          ))
        }

      </div>

    </div>
  );
};
