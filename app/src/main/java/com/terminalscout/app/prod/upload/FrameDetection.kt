package com.terminalscout.app.prod.upload

import android.graphics.Rect
import com.terminalscout.app.prod.pipeline.FuelPrice

/**
 * Per-frame detection record for a single frame that reached the reader (i.e. cleared both gates).
 * One of these becomes one entry in the payload's `detections` list. Every metric here is produced by
 * the pipeline for that frame; absent values are omitted from the JSON.
 *
 * Nothing builds these yet — `EnrichmentStage` passes an empty list, so `detections` is always `[]`.
 * The per-frame metrics have to be persisted through the stages first; see the KNOWN GAPS note there.
 */
data class FrameDetection(
    val frameIndex: Int,
    /** Source-video timestamp of the frame, in microseconds (0 if unknown). */
    val timestampUs: Long,
    /** Board bounding box on the source frame, or null if none was detected. */
    val boardBbox: Rect?,
    /** YOLO board-detection confidence for the chosen box. */
    val confidence: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    /** OCR-suitability clarity score (0..1) from the image-prep stage's clarity scorer. */
    val clarityScore: Double,
    val sharpness: Double,
    val contrast: Double,
    /** What the reader read off this frame (fuel rows); empty if it read nothing. */
    val readings: List<FuelPrice> = emptyList()
)
