package com.terminalscout.app.prod.config

import com.terminalscout.app.prod.BuildConfig

/**
 * Central configuration for the on-device ML models — the single place to manage each model's
 * version, download location, on-disk filename, display name, and (for the LLM) inference params.
 *
 * To roll a new model build, bump the `VERSION` (the download URL and payload version follow from
 * it) and ship the new blob; nothing else in the codebase needs editing.
 *
 * Consumed by:
 *   - `download/ModelRepository` — builds its download specs (URL + filename + label) from here.
 *   - `upload/ReadingPayload` — stamps [PAYLOAD_MODEL_VERSION] into every uploaded payload.
 *
 * NOTE: pipeline TUNING (YOLO confidence gate, frame cap, recency half-life, sampling rate) lives
 * with the pipeline — this file is model IDENTITY/metadata + LLM inference config only.
 */
object ModelConfig {

    // The blob host used to live here, because the app signed its own SAS against it. It no longer
    // knows any storage URL: models are named `<container>/<blob>` and the Device API returns a
    // short-lived download URL for each (see download/DeviceApi).

    /**
     * The on-device vision LLM — **not part of this build**, and reduced to the four constants that
     * still have to exist.
     *
     * Stage 4 reads off-device ([com.terminalscout.app.prod.pipeline.LunaOcrReader]); there is no
     * LiteRT-LM engine in the APK, nothing downloads this model (it is excluded from
     * `ModelRepository.ALL`), and nothing opens the file. The sampler parameters and the
     * GPU-vision-encoder switch that used to sit here are gone with the engine they configured — they
     * described inference this app does not perform.
     *
     * What is left is only what `download/ModelRepository.GEMMA` reads. That file is FROZEN
     * (`freeze_files.md`), so the spec cannot be deleted from it here; removing these four constants
     * would stop the project compiling. See the note on [PAYLOAD_MODEL_VERSION] for the one place this
     * absence was doing real damage.
     */
    object Gemma {
        const val KEY = "gemma"
        const val DISPLAY_NAME = "Gemma 4 E2B (vision model, not used)"
        const val VERSION = "gemma-4-e2b-001"

        /** Name passed to `GET /v1/models/{container}/{blob}`. */
        const val API_NAME = "llm/$VERSION.litertlm"

        /** On-device filename under filesDir/models — versioned so a model swap re-downloads. */
        const val FILE_NAME = "$VERSION.litertlm"
    }

    /** YOLO board-detection model (TFLite) — locates the price board in each frame. */
    object Yolo {
        const val KEY = "yolo"
        const val DISPLAY_NAME = "Board detector (YOLO)"

        const val VERSION = "board-detection-001"

        /** Name passed to `GET /v1/models/{container}/{blob}`. */
        const val API_NAME = "yolo/$VERSION.tflite"

        /** On-device filename under filesDir/models. */
        const val FILE_NAME = "board-detection.tflite"

        /**
         * Test override: a board-detection model shipped INSIDE the APK under `assets/`, so a new
         * detector can be tried without publishing a blob and bumping [VERSION].
         *
         * When non-null, Stage 2 memory-maps this asset and the downloaded [FILE_NAME] is never
         * opened — so the model is also dropped from [download.ModelRepository.ALL] and its row is
         * hidden on the download screen, rather than making the user wait on a 10 MB file nothing
         * reads. Set to null to go back to the downloaded model; nothing else needs editing.
         *
         * The asset must stay uncompressed for the interpreter to map it — `androidResources
         * { noCompress += "tflite" }` in app/build.gradle.kts is what guarantees that.
         */
        val BUNDLED_ASSET: String? = null

        /** Stamped into uploaded payloads while [BUNDLED_ASSET] is in use, in place of [VERSION]. */
        const val BUNDLED_VERSION = "board-detection-v2"

        /** The version of the model Stage 2 will actually load — bundled asset wins when set. */
        val ACTIVE_VERSION: String get() = if (BUNDLED_ASSET != null) BUNDLED_VERSION else VERSION
    }

    /**
     * YOLO price-region model (TFLite) — locates each individual price panel on a detected board, so
     * Stage 3 can outline and letter them. Used by `pipeline/ImagePrepStage`.
     *
     * Downloaded like every other model rather than bundled: it used to ship in `assets/` as
     * `price-region-v2.tflite`, which put 10 MB into every APK and meant a model swap needed a new
     * build. It now sits in the same blob container as the board detector.
     */
    object PriceRegion {
        const val KEY = "price_region"
        const val DISPLAY_NAME = "Price-region detector (YOLO)"

        const val VERSION = "price-region-002"

        /** Name passed to `GET /v1/models/{container}/{blob}` — same container as the board detector. */
        const val API_NAME = "yolo/$VERSION.tflite"

        /**
         * On-device filename under filesDir/models. VERSIONED, unlike [Yolo.FILE_NAME]: bumping
         * [VERSION] then changes the path, so the new build downloads instead of silently reusing the
         * old file already on disk.
         */
        const val FILE_NAME = "$VERSION.tflite"
    }

    /**
     * Combined model identifier stamped into every uploaded payload's `model_version` field.
     *
     * **It used to name Gemma.** `"gemma-4-e2b-001+board-detection-v2"` went out on every reading this
     * app has ever produced, and no part of the run had touched Gemma: the digits are read off-device
     * by [com.terminalscout.app.prod.BuildConfig.AZURE_AI_MODEL] on the Responses API. The field a
     * consumer would use to tell one model's readings from another's named the wrong model — the worst
     * kind of wrong, because it is plausible and nothing downstream can contradict it.
     *
     * All three models that actually shape a reading are named, in the order they act: the board
     * detector finds the post, the region model finds the panels, the reader reads the digits. The
     * detector reads [Yolo.ACTIVE_VERSION] rather than [Yolo.VERSION], so a bundled test model is
     * named as itself.
     */
    val PAYLOAD_MODEL_VERSION: String
        get() = "${Yolo.ACTIVE_VERSION}+${PriceRegion.VERSION}+${BuildConfig.AZURE_AI_MODEL}"
}
