'use client';

import React from 'react';
import { useAnnotationStore } from '../../store/useAnnotationStore';


/**
 * The pipeline trains on exactly two classes, board and price region, and each
 * workflow stage annotates one of them. Classes are therefore fixed: no adding,
 * renaming or deleting.
 */
export const ClassList: React.FC = () => {
  const {
    classes,
    annotations,
    selectedImageId,
    stage
  } = useAnnotationStore();

  const stageClassId =
    stage === 'price_region'
      ? 'class-price-region'
      : 'class-board';

  const getClassCounts = (classId: string) => {
    const total = annotations.filter(ann => ann.classId === classId).length;
    const current = annotations.filter(
      ann => ann.classId === classId && ann.imageId === selectedImageId
    ).length;
    return { total, current };
  };

  return (
    <div className="flex flex-col h-full text-zinc-300">
      <div className="flex-1 overflow-y-auto p-2 space-y-1.5">
        {classes.map(cls => {
          const { total, current } = getClassCounts(cls.id);
          const isActive = cls.id === stageClassId;

          return (
            <div
              key={cls.id}
              title={
                isActive
                  ? 'Drawn boxes get this label in the current stage'
                  : 'Not annotated in the current stage'
              }
              className={`flex items-center justify-between p-2 rounded-lg border transition-all ${
                isActive
                  ? 'bg-[#1a1a1e] border-blue-500/50'
                  : 'bg-[#141416] border-zinc-800/40 opacity-50'
              }`}
            >
              <div className="flex items-center gap-2.5 min-w-0 flex-1">
                <div
                  className="w-4 h-4 rounded-md border border-black/20 shrink-0"
                  style={{ backgroundColor: cls.color }}
                />

                <div className="flex items-center gap-1.5 min-w-0">
                  <span className="text-xs font-medium truncate">{cls.name}</span>
                  {total > 0 && (
                    <span
                      title={`${current} on this frame, ${total} in this video`}
                      className="text-[9px] text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded-full font-mono font-bold shrink-0 cursor-help"
                    >
                      {total}
                    </span>
                  )}
                </div>
              </div>

              {isActive && (
                <span className="text-[9px] uppercase font-bold tracking-wider text-blue-400 shrink-0 ml-2">
                  active
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};
