'use client';

import React, {
  useState,
  useEffect,
  useRef
} from 'react';

import {
  Stage,
  Layer,
  Image as KonvaImage,
  Rect,
  Line,
  Group,
  Transformer
} from 'react-konva';

import Konva from 'konva';

import {
  useAnnotationStore
} from '../../store/useAnnotationStore';

import { Annotation } from '../../types';

import {
  VideoMetadata,
  formatCoordinates,
  formatTimestamp
} from '../../utils/videoMetadata';


interface AnnotationCanvasProps {
  containerWidth: number;
  containerHeight: number;
  // Capture details from the video's api_response.json.
  metadata: VideoMetadata;
}


export const AnnotationCanvas:
React.FC<AnnotationCanvasProps> = ({
  containerWidth,
  containerHeight,
  metadata
}) => {

  const {
    images,
    classes,
    annotations,
    selectedImageId,
    selectedAnnotationId,
    canvasSettings,
    setSelectedAnnotationId,
    addAnnotation,
    deleteAnnotation,
    updateAnnotationCoordinates,
    updateCanvasSettings,
    setImageDimensions
  } = useAnnotationStore();


  // ============================================================
  // SELECTED IMAGE / ANNOTATIONS
  // ============================================================

  const selectedImage =
    images.find(
      img =>
        img.id === selectedImageId
    );


  const imageAnns =
    annotations.filter(
      ann =>
        ann.imageId === selectedImageId
    );


  // Resizing a board on its frame changes the window without changing the
  // image behind it, so the view has to refit around the new one.
  const cropKey =
    selectedImage?.cropBox?.join(',') ?? '';


  // ============================================================
  // STATES
  // ============================================================

  const [
    imageElement,
    setImageElement
  ] = useState<HTMLImageElement | null>(
    null
  );

const [
    drawingState,
    setDrawingState
  ] = useState<{
    isDrawing: boolean;

    startPoint: {
      x: number;
      y: number;
    } | null;

    currentMousePos: {
      x: number;
      y: number;
    } | null;
  }>({
    isDrawing: false,
    startPoint: null,
    currentMousePos: null
  });


  const [
    hoveredAnnId,
    setHoveredAnnId
  ] = useState<string | null>(
    null
  );


  const [
    isSpacePressed,
    setIsSpacePressed
  ] = useState(false);


  const [
    stageDimensions,
    setStageDimensions
  ] = useState({
    width: 800,
    height: 600
  });


  // ============================================================
  // REFS
  // ============================================================

  const stageRef =
    useRef<Konva.Stage>(
      null
    );


  const layerRef =
    useRef<Konva.Layer>(
      null
    );


  const transformerRef =
    useRef<Konva.Transformer>(
      null
    );


  const rectNodeRefs =
    useRef<{
      [id: string]:
        Konva.Rect | null;
    }>({});


  // ============================================================
  // SYNC CANVAS SIZE
  // ============================================================

  useEffect(
    () => {

      if (
        containerWidth > 0 &&
        containerHeight > 0
      ) {

        setStageDimensions({
          width:
            containerWidth,

          height:
            containerHeight
        });
      }

    },
    [
      containerWidth,
      containerHeight
    ]
  );


// ============================================================
  // LOAD SELECTED IMAGE
  // ============================================================

  useEffect(() => {
    if (!selectedImage || !selectedImage.url) {
      setImageElement(null);
      return;
    }

    const img = new window.Image();

    // Configure CORS before starting the request so Konva can safely use
    // the image as a canvas source.
    img.crossOrigin = 'anonymous';

    img.onload = () => {
      setImageElement(img);

      // Images loaded from Azure arrive without a known size, so record the
      // natural size here; it is saved alongside the annotations.
      if (img.naturalWidth > 0 && img.naturalHeight > 0) {
        setImageDimensions(
          selectedImage.id,
          img.naturalWidth,
          img.naturalHeight
        );
      }

      // A board shows only a window into its frame, so what has to fit the
      // canvas is the window, not the whole frame behind it.
      const crop = selectedImage.cropBox;

      const shownWidth = crop
        ? Math.max(1, Math.round(crop[2] * img.naturalWidth))
        : img.naturalWidth;

      const shownHeight = crop
        ? Math.max(1, Math.round(crop[3] * img.naturalHeight))
        : img.naturalHeight;

      // Fit the selected image into the available canvas.
      if (
        containerWidth > 0 &&
        containerHeight > 0 &&
        shownWidth > 0 &&
        shownHeight > 0
      ) {
        const fitZoom = Math.min(
          (containerWidth - 60) / shownWidth,
          (containerHeight - 60) / shownHeight,
          1.5
        );

        const zoom = Math.max(0.05, fitZoom);

        updateCanvasSettings({
          zoom,
          panX: (containerWidth - shownWidth * zoom) / 2,
          panY: (containerHeight - shownHeight * zoom) / 2,
        });
      }
    };

    img.onerror = (event) => {
      console.error(
        'Canvas image failed to load:',
        selectedImage.name,
        selectedImage.url,
        event
      );

      setImageElement(null);
    };

    // Important: selectedImage.url is the same URL that works in ImageSidebar.
    img.src = selectedImage.url;

    return () => {
      img.onload = null;
      img.onerror = null;
    };
  }, [
    selectedImage?.id,
    selectedImage?.url,
    cropKey,
    containerWidth,
    containerHeight,
    updateCanvasSettings,
    setImageDimensions,
  ]);


// ============================================================
  // KEYBOARD SHORTCUTS
  // ============================================================

  useEffect(
    () => {

      const handleKeyDown =
        (
          e: KeyboardEvent
        ) => {

          const activeElement =
            document.activeElement;


          const isInput =
            activeElement &&
            (
              activeElement.tagName ===
                'INPUT' ||

              activeElement.tagName ===
                'TEXTAREA' ||

              activeElement
                .getAttribute(
                  'contenteditable'
                ) ===
                'true'
            );


          if (
            isInput
          ) {
            return;
          }


          // SPACE = PAN
          if (
            e.code ===
            'Space'
          ) {

            e.preventDefault();


            if (
              !isSpacePressed
            ) {

              setIsSpacePressed(
                true
              );
            }
          }


          // B = Bounding Box
          else if (
            e.key.toLowerCase() ===
            'b'
          ) {

            updateCanvasSettings({
              tool:
                'bbox'
            });
          }


          // S / ESCAPE = SELECT
          else if (
            e.key.toLowerCase() ===
              's' ||

            e.key ===
              'Escape'
          ) {

            updateCanvasSettings({
              tool:
                'select'
            });


            setDrawingState({
              isDrawing:
                false,

              startPoint:
                null,


              currentMousePos:
                null
            });
          }


          // DELETE
          else if (
            e.key ===
              'Delete' ||

            e.key ===
              'Backspace'
          ) {

            if (
              selectedAnnotationId
            ) {

              deleteAnnotation(
                selectedAnnotationId
              );
            }
          }
        };


      const handleKeyUp =
        (
          e: KeyboardEvent
        ) => {

          if (
            e.code ===
            'Space'
          ) {

            setIsSpacePressed(
              false
            );
          }
        };


      window.addEventListener(
        'keydown',
        handleKeyDown
      );


      window.addEventListener(
        'keyup',
        handleKeyUp
      );


      return () => {

        window.removeEventListener(
          'keydown',
          handleKeyDown
        );


        window.removeEventListener(
          'keyup',
          handleKeyUp
        );
      };

    },
    [
      selectedAnnotationId,
      isSpacePressed,
      deleteAnnotation,
      updateCanvasSettings
    ]
  );


  // ============================================================
  // TRANSFORMER SELECTION
  // ============================================================

  useEffect(
    () => {

      if (
        !transformerRef.current
      ) {
        return;
      }


      if (
        selectedAnnotationId
      ) {

        const selectedAnn =
          imageAnns.find(
            ann =>
              ann.id ===
              selectedAnnotationId
          );


        if (
          selectedAnn &&
          selectedAnn.type ===
            'rect'
        ) {

          const node =
            rectNodeRefs.current[
              selectedAnnotationId
            ];


          if (
            node
          ) {

            transformerRef
              .current
              .nodes([
                node
              ]);


            transformerRef
              .current
              .getLayer()
              ?.batchDraw();


            return;
          }
        }
      }


      transformerRef.current.nodes(
        []
      );


      transformerRef
        .current
        .getLayer()
        ?.batchDraw();

    },
    [
      selectedAnnotationId,
      imageAnns
    ]
  );


  // ============================================================
  // SELECTED IMAGE
  // ============================================================

  if (!selectedImage) {
    return (
      <div className="flex items-center justify-center w-full h-full bg-[#0a0a0c] text-zinc-400">
        No Image Selected
      </div>
    );
  }

  if (!imageElement) {
    return (
      <div className="flex items-center justify-center w-full h-full bg-[#0a0a0c] text-zinc-400">
        Loading {selectedImage.name}...
      </div>
    );
  }

  // What the canvas draws on. For a board this is the crop window, so every
  // box drawn here is already normalised against the crop the dataset will
  // eventually contain.
  const cropBox = selectedImage.cropBox;

  const naturalWidth = imageElement.naturalWidth || 800;
  const naturalHeight = imageElement.naturalHeight || 600;

  const imgW = cropBox
    ? Math.max(1, Math.round(cropBox[2] * naturalWidth))
    : selectedImage.width || naturalWidth;

  const imgH = cropBox
    ? Math.max(1, Math.round(cropBox[3] * naturalHeight))
    : selectedImage.height || naturalHeight;


  // ============================================================
  // ZOOM
  // ============================================================

  const handleWheel =
    (
      e:
        Konva.KonvaEventObject<
          WheelEvent
        >
    ) => {

      e.evt.preventDefault();


      const stage =
        stageRef.current;


      if (
        !stage
      ) {
        return;
      }


      const oldScale =
        canvasSettings.zoom;


      const pointer =
        stage.getPointerPosition();


      if (
        !pointer
      ) {
        return;
      }


      const layer =
        layerRef.current;


      if (
        !layer
      ) {
        return;
      }


      // Zoom about the point under the cursor, found the same way as when
      // drawing so the two never disagree.
      const mousePointTo =
        layer
          .getAbsoluteTransform()
          .copy()
          .invert()
          .point(pointer);


      const zoomSpeed =
        1.15;


      const direction =
        e.evt.deltaY < 0
          ? 1
          : -1;


      const newScale =
        direction > 0
          ? oldScale *
            zoomSpeed
          : oldScale /
            zoomSpeed;


      const clampedScale =
        Math.max(
          0.05,
          Math.min(
            30,
            newScale
          )
        );


      updateCanvasSettings({
        zoom:
          clampedScale,

        panX:
          pointer.x -
          mousePointTo.x *
            clampedScale,

        panY:
          pointer.y -
          mousePointTo.y *
            clampedScale
      });
    };


  // ============================================================
  // END OF PAN
  //
  // Panning moves the Stage itself, so on release the offset is
  // folded into panX/panY and the Stage is put back at the
  // origin. Without this the pointer and zoom maths, which are
  // written against panX/panY, drift by the drag distance.
  // ============================================================

  const handleStageDragEnd =
    () => {

      const stage =
        stageRef.current;


      if (
        !stage
      ) {
        return;
      }


      const {
        x,
        y
      } = stage.position();


      if (
        x === 0 &&
        y === 0
      ) {
        return;
      }


      const nextPanX =
        canvasSettings.panX + x;


      const nextPanY =
        canvasSettings.panY + y;


      // Move both nodes in the same tick, so the total offset never changes
      // and the image does not flick back before React re-renders.
      stage.position({
        x: 0,
        y: 0
      });


      layerRef.current?.position({
        x: nextPanX,
        y: nextPanY
      });


      updateCanvasSettings({
        panX: nextPanX,
        panY: nextPanY
      });
    };


  // ============================================================
  // NORMALIZED POINTER
  // ============================================================

  const getNormalizedPointer =
    (): {
      x: number;
      y: number;
    } | null => {

      const stage =
        stageRef.current;


      if (
        !stage
      ) {
        return null;
      }


      const layer =
        layerRef.current;


      const pointer =
        stage.getPointerPosition();


      if (
        !pointer ||
        !layer
      ) {
        return null;
      }


      // The image sits at the Layer origin, so a pointer mapped into Layer
      // space is already in image pixels.
      const imagePoint =
        layer
          .getAbsoluteTransform()
          .copy()
          .invert()
          .point(pointer);


      const imageX =
        imagePoint.x;


      const imageY =
        imagePoint.y;


      const normX =
        Math.max(
          0,
          Math.min(
            1,
            imageX /
              imgW
          )
        );


      const normY =
        Math.max(
          0,
          Math.min(
            1,
            imageY /
              imgH
          )
        );


      return {
        x:
          normX,

        y:
          normY
      };
    };


  // ============================================================
  // MOUSE DOWN
  // ============================================================

  const handleMouseDown =
    (
      e:
        Konva.KonvaEventObject<
          MouseEvent
        >
    ) => {

      const isPanning =
        canvasSettings.tool ===
          'pan' ||
        isSpacePressed;


      if (
        isPanning
      ) {
        return;
      }


      if (
        e.target === stageRef.current ||
        e.target.name() === 'background-image'
      ) {

        setSelectedAnnotationId(
          null
        );
      }


      const normPointer =
        getNormalizedPointer();


      if (
        !normPointer
      ) {
        return;
      }


      // Existing behaviour:
      // first class is default.
      // A crop window means this is a board being labelled for prices;
      // the whole frame means boards are being drawn on it.
      const activeClass =
        classes.find(cls =>
          cls.id === (
            selectedImage.cropBox
              ? 'class-price-region'
              : 'class-board'
          )
        ) || classes[0];


      if (
        !activeClass
      ) {
        return;
      }


      if (
        canvasSettings.tool ===
        'bbox'
      ) {

        setDrawingState({
          isDrawing:
            true,

          startPoint:
            normPointer,


          currentMousePos:
            normPointer
        });
      }
    };


  // ============================================================
  // MOUSE MOVE
  // ============================================================

  const handleMouseMove =
    (
      e:
        Konva.KonvaEventObject<
          MouseEvent
        >
    ) => {

      const normPointer =
        getNormalizedPointer();


      if (
        !normPointer
      ) {
        return;
      }


      setDrawingState(
        prev => ({
          ...prev,

          currentMousePos:
            normPointer
        })
      );


      if (
        !drawingState.isDrawing
      ) {
        return;
      }
    };


  // ============================================================
  // MOUSE UP / CREATE BBOX
  // ============================================================

  const handleMouseUp =
    () => {

      if (
        !drawingState.isDrawing
      ) {
        return;
      }


      const normPointer =
        drawingState.currentMousePos;


      // A crop window means this is a board being labelled for prices;
      // the whole frame means boards are being drawn on it.
      const activeClass =
        classes.find(cls =>
          cls.id === (
            selectedImage.cropBox
              ? 'class-price-region'
              : 'class-board'
          )
        ) || classes[0];


      if (
        canvasSettings.tool ===
          'bbox' &&

        drawingState.startPoint &&

        normPointer &&

        activeClass
      ) {

        const x =
          Math.min(
            drawingState
              .startPoint
              .x,

            normPointer.x
          );


        const y =
          Math.min(
            drawingState
              .startPoint
              .y,

            normPointer.y
          );


        const w =
          Math.abs(
            drawingState
              .startPoint
              .x -
            normPointer.x
          );


        const h =
          Math.abs(
            drawingState
              .startPoint
              .y -
            normPointer.y
          );


        if (
          w > 0.005 &&
          h > 0.005
        ) {

          const newAnn:
            Annotation = {

            id:
              `ann-${Date.now()}-${Math.random()
                .toString(36)
                .substr(2, 9)}`,

            imageId:
              selectedImage.id,

            classId:
              activeClass.id,

            type:
              'rect',

            coordinates:
              [
                x,
                y,
                w,
                h
              ]
          };


          addAnnotation(
            newAnn
          );
        }


        setDrawingState({
          isDrawing:
            false,

          startPoint:
            null,


          currentMousePos:
            null
        });
      }
    };


  // ============================================================
  // BBOX RESIZE
  // ============================================================

  const handleRectTransformEnd =
    (
      annId: string
    ) => {

      const node =
        rectNodeRefs.current[
          annId
        ];


      if (
        !node
      ) {
        return;
      }


      const scaleX =
        node.scaleX();


      const scaleY =
        node.scaleY();


      const x =
        Math.max(
          0,
          Math.min(
            1,
            node.x() /
              imgW
          )
        );


      const y =
        Math.max(
          0,
          Math.min(
            1,
            node.y() /
              imgH
          )
        );


      const w =
        Math.max(
          0.005,
          Math.min(
            1 - x,
            (
              node.width() *
              scaleX
            ) /
              imgW
          )
        );


      const h =
        Math.max(
          0.005,
          Math.min(
            1 - y,
            (
              node.height() *
              scaleY
            ) /
              imgH
          )
        );


      node.scaleX(
        1
      );


      node.scaleY(
        1
      );


      updateAnnotationCoordinates(
        annId,
        [
          x,
          y,
          w,
          h
        ]
      );
    };


  // ============================================================
  // BBOX DRAG
  // ============================================================

  const handleRectDragEnd =
    (
      annId: string
    ) => {

      const node =
        rectNodeRefs.current[
          annId
        ];


      if (
        !node
      ) {
        return;
      }


      const x =
        Math.max(
          0,
          Math.min(
            1,
            node.x() /
              imgW
          )
        );


      const y =
        Math.max(
          0,
          Math.min(
            1,
            node.y() /
              imgH
          )
        );


      const w =
        Math.min(
          1 - x,
          node.width() /
            imgW
        );


      const h =
        Math.min(
          1 - y,
          node.height() /
            imgH
        );


      updateAnnotationCoordinates(
        annId,
        [
          x,
          y,
          w,
          h
        ]
      );
    };


  // ============================================================
  // POLYGON VERTEX MOVE
  // ============================================================
  // POINT DRAG
  // ============================================================
  // NORMALIZED → PIXEL POINTS
  // ============================================================
  // UI
  // ============================================================

  return (

    <div
      className="
        relative
        w-full
        h-full
        select-none
        overflow-hidden
        bg-[#0d0d10]
      "
    >

      {/* ===================================================== */}
      {/* CANVAS INFO */}
      {/* ===================================================== */}

      <div
        className="
          absolute
          bottom-4
          left-4
          z-10
          pointer-events-none
          flex
          flex-col
          gap-1
          text-[11px]
          font-mono
          text-zinc-400
          bg-black/60
          px-3
          py-2
          rounded
          border
          border-zinc-800
        "
      >

        <div>
          Zoom:{' '}
          {
            Math.round(
              canvasSettings.zoom *
              100
            )
          }%
        </div>


        <div>
          Image:{' '}
          {imgW}
          {' x '}
          {imgH}
          px
        </div>


        {
          metadata.timestamp
          && (

            <div
              className="
                text-zinc-500
              "
            >
              Time:{' '}
              {
                formatTimestamp(
                  metadata.timestamp
                )
              }
            </div>
          )
        }


        {
          metadata.latitude !== undefined &&
          metadata.longitude !== undefined
          && (

            <div
              className="
                text-zinc-500
              "
              title={
                metadata.gpsAccuracyM !== undefined
                  ? `GPS accuracy ${metadata.gpsAccuracyM} m`
                  : undefined
              }
            >
              Lat/Lon:{' '}
              {
                formatCoordinates(
                  metadata.latitude,
                  metadata.longitude
                )
              }
            </div>
          )
        }


        {
          drawingState
            .currentMousePos
          && (

            <div>

              X:{' '}

              {
                Math.round(
                  drawingState
                    .currentMousePos
                    .x *
                  imgW
                )
              }

              {' '}

              Y:{' '}

              {
                Math.round(
                  drawingState
                    .currentMousePos
                    .y *
                  imgH
                )
              }

            </div>
          )
        }

      </div>


      {/* ===================================================== */}
      {/* GRID BACKGROUND */}
      {/* ===================================================== */}

      {
        canvasSettings
          .gridVisible
        && (

          <div
            className="
              absolute
              inset-0
              pointer-events-none
              bg-[linear-gradient(to_right,#1f2937_1px,transparent_1px),linear-gradient(to_bottom,#1f2937_1px,transparent_1px)]
              bg-[size:40px_40px]
              opacity-15
            "
          />

        )
      }


      {/* ===================================================== */}
            {/* ===================================================== */}
      {/* KONVA STAGE */}
      {/* ===================================================== */}
<Stage

        ref={
          stageRef
        }

        width={
          stageDimensions.width
        }

        height={
          stageDimensions.height
        }

        onWheel={
          handleWheel
        }

        onMouseDown={
          handleMouseDown
        }

        onMouseMove={
          handleMouseMove
        }

        onMouseUp={
          handleMouseUp
        }

        draggable={
          canvasSettings.tool ===
            'pan' ||
          isSpacePressed
        }

        onDragEnd={
          handleStageDragEnd
        }

        style={{

          cursor:
            isSpacePressed ||
            canvasSettings.tool ===
              'pan'

              ? 'grab'

              : canvasSettings.tool !==
                  'select'

                ? 'crosshair'

                : 'default'
        }}
      >


        <Layer

          ref={
            layerRef
          }

          x={
            canvasSettings.panX
          }

          y={
            canvasSettings.panY
          }

          scaleX={
            canvasSettings.zoom
          }

          scaleY={
            canvasSettings.zoom
          }
        >


          {/* ================================================= */}
          {/* IMAGE */}
          {/* ================================================= */}

          <KonvaImage
            image={imageElement}
            width={imgW}
            height={imgH}
            crop={
              cropBox
                ? {
                    x: cropBox[0] * naturalWidth,
                    y: cropBox[1] * naturalHeight,
                    width: imgW,
                    height: imgH
                  }
                : undefined
            }
            name="background-image"
          />

{/* ================================================= */}
          {/* OPTIONAL GRID INSIDE IMAGE */}
          {/* ================================================= */}

          {
            canvasSettings
              .gridVisible
            && (

              <Group>

                {
                  Array
                    .from({
                      length:
                        Math.ceil(
                          imgW /
                          50
                        )
                    })
                    .map(
                      (
                        _,
                        i
                      ) => (

                        <Line

                          key={
                            `v-${i}`
                          }

                          points={[
                            i *
                              50,
                            0,
                            i *
                              50,
                            imgH
                          ]}

                          stroke="#3f3f46"

                          strokeWidth={
                            0.5 /
                            canvasSettings.zoom
                          }

                          opacity={
                            0.3
                          }

                        />

                      )
                    )
                }


                {
                  Array
                    .from({
                      length:
                        Math.ceil(
                          imgH /
                          50
                        )
                    })
                    .map(
                      (
                        _,
                        i
                      ) => (

                        <Line

                          key={
                            `h-${i}`
                          }

                          points={[
                            0,
                            i *
                              50,
                            imgW,
                            i *
                              50
                          ]}

                          stroke="#3f3f46"

                          strokeWidth={
                            0.5 /
                            canvasSettings.zoom
                          }

                          opacity={
                            0.3
                          }

                        />

                      )
                    )
                }

              </Group>

            )
          }


          {/* ================================================= */}
          {/* SAVED ANNOTATIONS */}
          {/* ================================================= */}

          {
            imageAnns.map(
              ann => {

                const cls =
                  classes.find(
                    item =>
                      item.id ===
                      ann.classId
                  ) || {
                    color:
                      '#ffffff',

                    name:
                      'Unknown'
                  };


                const isSelected =
                  ann.id ===
                  selectedAnnotationId;


                const isHovered =
                  ann.id ===
                  hoveredAnnId;


                const color =
                  cls.color;


                const strokeWidth =
                  (
                    isSelected
                      ? canvasSettings
                          .brushWidth *
                        1.5
                      : canvasSettings
                          .brushWidth
                  ) /
                  canvasSettings.zoom;


                const fillOpacity =
                  isSelected

                    ? canvasSettings
                        .opacity +
                      0.15

                    : isHovered

                      ? canvasSettings
                          .opacity +
                        0.05

                      : canvasSettings
                          .opacity;


                // --------------------------------------------
                // RECTANGLE
                // --------------------------------------------

                if (
                  ann.type ===
                  'rect'
                ) {

                  const [
                    x,
                    y,
                    w,
                    h
                  ] =
                    ann.coordinates;


                  const absX =
                    x *
                    imgW;


                  const absY =
                    y *
                    imgH;


                  const absW =
                    w *
                    imgW;


                  const absH =
                    h *
                    imgH;


                  return (

                    <Rect

                      key={
                        ann.id
                      }

                      ref={
                        el => {

                          rectNodeRefs
                            .current[
                              ann.id
                            ] =
                            el;
                        }
                      }

                      x={
                        absX
                      }

                      y={
                        absY
                      }

                      width={
                        absW
                      }

                      height={
                        absH
                      }

                      stroke={
                        color
                      }

                      strokeWidth={
                        strokeWidth
                      }

                      fill={
                        `${color}33`
                      }

                      opacity={
                        fillOpacity
                      }

                      draggable={
                        canvasSettings.tool ===
                        'select'
                      }

                      onMouseEnter={
                        () =>
                          setHoveredAnnId(
                            ann.id
                          )
                      }

                      onMouseLeave={
                        () =>
                          setHoveredAnnId(
                            null
                          )
                      }

                      onClick={
                        e => {

                          if (
                            canvasSettings.tool ===
                            'select'
                          ) {

                            e.cancelBubble =
                              true;


                            setSelectedAnnotationId(
                              ann.id
                            );
                          }
                        }
                      }

                      onDragEnd={
                        () =>
                          handleRectDragEnd(
                            ann.id
                          )
                      }

                      onTransformEnd={
                        () =>
                          handleRectTransformEnd(
                            ann.id
                          )
                      }

                    />

                  );
                }


                return null;
              }
            )
          }


          {/* ================================================= */}
          {/* TEMPORARY BBOX */}
          {/* ================================================= */}

          {
            canvasSettings.tool ===
              'bbox'
            &&
            drawingState.isDrawing
            &&
            drawingState.startPoint
            &&
            drawingState.currentMousePos
            && (

              <Rect

                x={
                  Math.min(
                    drawingState
                      .startPoint
                      .x,

                    drawingState
                      .currentMousePos
                      .x
                  ) *
                  imgW
                }

                y={
                  Math.min(
                    drawingState
                      .startPoint
                      .y,

                    drawingState
                      .currentMousePos
                      .y
                  ) *
                  imgH
                }

                width={
                  Math.abs(
                    drawingState
                      .startPoint
                      .x -
                    drawingState
                      .currentMousePos
                      .x
                  ) *
                  imgW
                }

                height={
                  Math.abs(
                    drawingState
                      .startPoint
                      .y -
                    drawingState
                      .currentMousePos
                      .y
                  ) *
                  imgH
                }

                stroke={
                  classes[0]
                    ?.color ||
                  '#3b82f6'
                }

                strokeWidth={
                  canvasSettings
                    .brushWidth /
                  canvasSettings.zoom
                }

                fill={
                  `${
                    classes[0]
                      ?.color ||
                    '#3b82f6'
                  }1a`
                }

                dash={[
                  4,
                  4
                ]}

              />
            )
          }


          {/* ================================================= */}
                    {/* ================================================= */}
          {/* BBOX TRANSFORMER */}
          {/* ================================================= */}

          {
            selectedAnnotationId
            && (

              <Transformer

                ref={
                  transformerRef
                }

                boundBoxFunc={
                  (
                    oldBox,
                    newBox
                  ) => {

                    if (
                      newBox.width <
                        5 ||

                      newBox.height <
                        5
                    ) {

                      return oldBox;
                    }


                    return newBox;
                  }
                }

                rotateEnabled={
                  false
                }

                anchorStroke="#ffffff"

                anchorFill="#3b82f6"

                anchorSize={
                  8
                }

                borderStroke="#3b82f6"

                borderDash={[
                  3,
                  3
                ]}

              />

            )
          }


          {/* ================================================= */}
          {/* CROSSHAIR */}
          {/* ================================================= */}

          {
            canvasSettings.tool !==
              'select'
            &&
            canvasSettings.tool !==
              'pan'
            &&
            drawingState
              .currentMousePos
            && (

              <Group>


                <Line

                  points={[
                    0,

                    drawingState
                      .currentMousePos
                      .y *
                      imgH,

                    imgW,

                    drawingState
                      .currentMousePos
                      .y *
                      imgH
                  ]}

                  stroke="#ffffff"

                  strokeWidth={
                    0.5 /
                    canvasSettings.zoom
                  }

                  opacity={
                    0.3
                  }

                  dash={[
                    4,
                    4
                  ]}

                />


                <Line

                  points={[

                    drawingState
                      .currentMousePos
                      .x *
                      imgW,

                    0,

                    drawingState
                      .currentMousePos
                      .x *
                      imgW,

                    imgH

                  ]}

                  stroke="#ffffff"

                  strokeWidth={
                    0.5 /
                    canvasSettings.zoom
                  }

                  opacity={
                    0.3
                  }

                  dash={[
                    4,
                    4
                  ]}

                />

              </Group>
            )
          }

        </Layer>

      </Stage>

    </div>
  );
};
