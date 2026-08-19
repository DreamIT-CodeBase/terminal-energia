'use client';

import React, { useState } from 'react';
import { ClassList } from '../ClassManager/ClassList';
import { ObjectList } from '../ObjectManager/ObjectList';
import { Tag, Layers, FileJson, ChevronDown, ChevronUp } from 'lucide-react';
import { useAnnotationStore } from '../../store/useAnnotationStore';


interface RightSidebarProps {
  // Parsed video-id/api_response.json, shown read-only as pipeline context.
  apiResponse?: unknown;
}


export const RightSidebar: React.FC<RightSidebarProps> = ({ apiResponse }) => {
  const { annotations, selectedImageId } = useAnnotationStore();
  const [classesOpen, setClassesOpen] = useState(true);
  const [objectsOpen, setObjectsOpen] = useState(true);
  const [apiResponseOpen, setApiResponseOpen] = useState(false);

  const activeImageAnnsCount = annotations.filter(ann => ann.imageId === selectedImageId).length;

  const bothListsOpen = classesOpen && objectsOpen;

  return (
    <aside className="w-64 flex flex-col h-full bg-[#141416] border-l border-[#232326] select-none text-zinc-300">
      {/* 1. Classes Section */}
      <div className={`flex flex-col min-h-0 ${bothListsOpen ? 'flex-1' : classesOpen ? 'flex-1' : 'h-auto'} border-b border-[#232326] transition-all duration-200`}>
        <div
          onClick={() => setClassesOpen(!classesOpen)}
          className="flex items-center justify-between px-3 h-10 bg-[#19191c] hover:bg-[#202024] cursor-pointer transition-colors border-b border-[#232326]/50 shrink-0"
        >
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-zinc-400">
            <Tag className="w-3.5 h-3.5 text-blue-500" />
            <span>Labels</span>
          </div>
          {classesOpen ? <ChevronUp className="w-4 h-4 text-zinc-500" /> : <ChevronDown className="w-4 h-4 text-zinc-500" />}
        </div>
        {classesOpen && (
          <div className="flex-1 min-h-0">
            <ClassList />
          </div>
        )}
      </div>

      {/* 2. Objects Section */}
      <div className={`flex flex-col min-h-0 ${bothListsOpen ? 'flex-1' : objectsOpen ? 'flex-1' : 'h-auto'} border-b border-[#232326] transition-all duration-200`}>
        <div
          onClick={() => setObjectsOpen(!objectsOpen)}
          className="flex items-center justify-between px-3 h-10 bg-[#19191c] hover:bg-[#202024] cursor-pointer transition-colors border-b border-[#232326]/50 shrink-0"
        >
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-zinc-400">
            <Layers className="w-3.5 h-3.5 text-blue-500" />
            <span>Objects ({activeImageAnnsCount})</span>
          </div>
          {objectsOpen ? <ChevronUp className="w-4 h-4 text-zinc-500" /> : <ChevronDown className="w-4 h-4 text-zinc-500" />}
        </div>
        {objectsOpen && (
          <div className="flex-1 min-h-0">
            <ObjectList />
          </div>
        )}
      </div>

      {/* 3. api_response.json — read-only pipeline context for this video */}
      <div className={`flex flex-col min-h-0 ${apiResponseOpen ? 'flex-1' : 'h-auto'} transition-all duration-200`}>
        <div
          onClick={() => setApiResponseOpen(!apiResponseOpen)}
          className="flex items-center justify-between px-3 h-10 bg-[#19191c] hover:bg-[#202024] cursor-pointer transition-colors border-b border-[#232326]/50 shrink-0"
        >
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-zinc-400">
            <FileJson className="w-3.5 h-3.5 text-blue-500" />
            <span>API response</span>
          </div>
          {apiResponseOpen ? <ChevronUp className="w-4 h-4 text-zinc-500" /> : <ChevronDown className="w-4 h-4 text-zinc-500" />}
        </div>
        {apiResponseOpen && (
          <div className="flex-1 min-h-0 overflow-auto p-2">
            {apiResponse ? (
              <pre className="text-[10px] leading-relaxed font-mono text-zinc-400 whitespace-pre-wrap break-all">
                {JSON.stringify(apiResponse, null, 2)}
              </pre>
            ) : (
              <div className="py-6 text-center text-[11px] text-zinc-600">
                No api_response.json for this video
              </div>
            )}
          </div>
        )}
      </div>
    </aside>
  );
};
