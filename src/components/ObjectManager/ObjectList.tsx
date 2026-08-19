'use client';

import React from 'react';
import { useAnnotationStore } from '../../store/useAnnotationStore';
import { Square, Hexagon, Activity, CircleDot, Trash2, Tag } from 'lucide-react';

export const ObjectList: React.FC = () => {
  const {
    images,
    classes,
    annotations,
    selectedImageId,
    selectedAnnotationId,
    setSelectedAnnotationId,
    deleteAnnotation,
    updateAnnotationClass
  } = useAnnotationStore();

  const activeImage = images.find(img => img.id === selectedImageId);
  const imageAnns = annotations.filter(ann => ann.imageId === selectedImageId);
  const workflowClassId =
    activeImage?.workflowStage === 'price_region'
      ? 'class-price-region'
      : activeImage?.workflowStage === 'board'
        ? 'class-board'
        : null;
  const availableClasses = workflowClassId
    ? classes.filter(cls => cls.id === workflowClassId)
    : classes;

  // Map shape types to corresponding icons
  const getShapeIcon = (type: string) => {
    switch (type) {
      case 'rect': return Square;
      case 'polygon': return Hexagon;
      case 'polyline': return Activity;
      case 'point': return CircleDot;
      default: return Tag;
    }
  };

  const getShapeLabel = (type: string) => {
    switch (type) {
      case 'rect': return 'Box';
      case 'polygon': return 'Polygon';
      case 'polyline': return 'Line';
      case 'point': return 'Point';
      default: return 'Object';
    }
  };

  return (
    <div className="flex flex-col h-full text-zinc-300">
      {/* Scrollable list content */}
      <div className="flex-1 overflow-y-auto p-2 space-y-1.5">
        {!activeImage ? (
          <div className="flex items-center justify-center py-8 text-zinc-600 text-xs">
            <span>No image selected</span>
          </div>
        ) : imageAnns.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-10 text-center text-zinc-500">
            <Tag className="w-8 h-8 text-zinc-700 mb-2" />
            <span className="text-xs">No shapes drawn yet</span>
            <span className="text-[10px] text-zinc-600 mt-1">Select a drawing tool on the left to start</span>
          </div>
        ) : (
          imageAnns.map((ann, idx) => {
            const cls = classes.find(c => c.id === ann.classId) || { id: '', name: 'Unknown', color: '#ffffff' };
            const isSelected = ann.id === selectedAnnotationId;
            const ShapeIcon = getShapeIcon(ann.type);

            return (
              <div
                key={ann.id}
                onClick={() => setSelectedAnnotationId(ann.id)}
                className={`flex items-center justify-between p-2 rounded-lg cursor-pointer transition-all border ${
                  isSelected 
                    ? 'bg-blue-600/10 border-blue-500 text-white' 
                    : 'bg-[#141416] hover:bg-[#1a1a1e] border-transparent hover:border-zinc-800'
                }`}
              >
                <div className="flex items-center gap-2.5 min-w-0 flex-1">
                  {/* Shape Type Icon */}
                  <div 
                    className="flex items-center justify-center w-6 h-6 rounded shrink-0"
                    style={{ backgroundColor: `${cls.color}22`, color: cls.color }}
                  >
                    <ShapeIcon className="w-3.5 h-3.5" />
                  </div>

                  {/* Class selection dropdown */}
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between">
                      <select
                        value={ann.classId}
                        onChange={(e) => {
                          updateAnnotationClass(ann.id, e.target.value);
                        }}
                        onClick={(e) => e.stopPropagation()} // Prevent selecting parent wrapper
                        className="bg-transparent text-xs font-semibold text-zinc-200 outline-none cursor-pointer hover:text-white max-w-[120px] truncate"
                      >
                        {availableClasses.map(c => (
                          <option key={c.id} value={c.id} className="bg-[#1f1f23] text-zinc-200">
                            {c.name}
                          </option>
                        ))}
                      </select>
                    </div>
                    
                    {/* Index identifier */}
                    <div className="text-[10px] text-zinc-500 font-mono mt-0.5">
                      {getShapeLabel(ann.type)} #{idx + 1}
                    </div>
                  </div>
                </div>

                {/* Delete button */}
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    deleteAnnotation(ann.id);
                  }}
                  title="Delete annotation"
                  className="p-1.5 hover:bg-red-500/10 rounded text-zinc-500 hover:text-red-400 transition-colors ml-2"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
