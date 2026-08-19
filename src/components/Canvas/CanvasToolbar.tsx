'use client';

import React from 'react';
import { useAnnotationStore } from '../../store/useAnnotationStore';
import { CanvasSettings } from '../../types';
import { MousePointer, Box, Hand, Trash2 } from 'lucide-react';


// Boxes are the only shape this pipeline trains on, so the toolbar carries
// just the three tools needed to draw, adjust and navigate them.
const TOOLS: {
  id: CanvasSettings['tool'];
  name: string;
  icon: typeof Box;
  hotkey: string;
}[] = [
  { id: 'select', name: 'Selection Tool', icon: MousePointer, hotkey: 'S' },
  { id: 'bbox', name: 'Bounding Box', icon: Box, hotkey: 'B' },
  { id: 'pan', name: 'Pan Tool', icon: Hand, hotkey: 'Space' }
];


export const CanvasToolbar: React.FC = () => {
  const {
    canvasSettings,
    selectedAnnotationId,
    deleteAnnotation,
    updateCanvasSettings
  } = useAnnotationStore();

  const activeTool = canvasSettings.tool;

  const handleDeleteActive = () => {
    if (selectedAnnotationId) {
      deleteAnnotation(selectedAnnotationId);
    }
  };

  return (
    <div className="absolute top-4 left-4 z-10 flex flex-col gap-1.5 p-1.5 rounded-lg bg-[#141416]/90 border border-[#232326] shadow-xl backdrop-blur-md">
      {TOOLS.map(tool => {
        const Icon = tool.icon;
        const isActive = activeTool === tool.id;

        return (
          <button
            key={tool.id}
            onClick={() => updateCanvasSettings({ tool: tool.id })}
            title={`${tool.name} [${tool.hotkey}]`}
            className={`relative group flex items-center justify-center w-9 h-9 rounded transition-all ${
              isActive
                ? 'bg-blue-600 text-white shadow shadow-blue-500/20'
                : 'text-zinc-400 hover:bg-[#202024] hover:text-white'
            }`}
          >
            <Icon className="w-4.5 h-4.5" />

            {/* Tooltip */}
            <div className="absolute left-12 hidden group-hover:flex items-center z-20">
              <div className="bg-[#1f1f23] border border-zinc-800 text-white text-xs py-1 px-2 rounded shadow-lg whitespace-nowrap">
                {tool.name} <span className="text-zinc-500 ml-1.5 font-mono text-[10px]">[{tool.hotkey}]</span>
              </div>
            </div>
          </button>
        );
      })}

      {selectedAnnotationId && (
        <>
          <div className="h-px bg-[#232326] my-1" />
          <button
            onClick={handleDeleteActive}
            title="Delete Selected Box [Del]"
            className="flex items-center justify-center w-9 h-9 rounded text-red-400 hover:bg-red-500/10 hover:text-red-300 transition-all"
          >
            <Trash2 className="w-4.5 h-4.5" />
          </button>
        </>
      )}
    </div>
  );
};
