package com.terminalscout.app.prod.download

import android.content.Context
import com.terminalscout.app.prod.config.ModelConfig
import java.io.File

/**
 * Registry of the on-device ML models and where they live once downloaded.
 *
 * The models are NOT shipped in the APK — they are pulled once via the Device API (see
 * [ModelDownloadManager], which asks it for a short-lived download URL per model) into the app's private
 * [Context.getFilesDir]/models directory and then stay on the device across launches. The pipeline reads them from these paths:
 *   - [GEMMA] → [gemmaPath] (LiteRT-LM engine model)
 *   - [YOLO]  → [yoloFile]  (TFLite board-detection interpreter)
 */
object ModelRepository {

    /**
     * One downloadable model: its stable [key], display [label], API [name] and on-disk [fileName].
     *
     * [name] is `<container>/<blob>` for `GET /v1/models/…`, NOT a URL — the app cannot construct a
     * storage URL any more, and the one it downloads from is signed per request by the server.
     */
    data class ModelSpec(
        val key: String,
        val label: String,
        val name: String,
        val fileName: String
    )

    val GEMMA = ModelSpec(
        key = ModelConfig.Gemma.KEY,
        label = ModelConfig.Gemma.DISPLAY_NAME,
        name = ModelConfig.Gemma.API_NAME,
        fileName = ModelConfig.Gemma.FILE_NAME
    )

    val YOLO = ModelSpec(
        key = ModelConfig.Yolo.KEY,
        label = ModelConfig.Yolo.DISPLAY_NAME,
        name = ModelConfig.Yolo.API_NAME,
        fileName = ModelConfig.Yolo.FILE_NAME
    )

    val PRICE_REGION = ModelSpec(
        key = ModelConfig.PriceRegion.KEY,
        label = ModelConfig.PriceRegion.DISPLAY_NAME,
        name = ModelConfig.PriceRegion.API_NAME,
        fileName = ModelConfig.PriceRegion.FILE_NAME
    )

    /** True while Stage 2 is running a board detector bundled in the APK instead of a downloaded one. */
    val boardDetectorIsBundled: Boolean get() = ModelConfig.Yolo.BUNDLED_ASSET != null

    /**
     * Every model that must be present before the capture pipeline can run — what the download screen
     * shows and what gates its Proceed button.
     *
     * [GEMMA] is deliberately NOT here. Stage 4 reads off-device (`OcrStage.USE_CLOUD_OCR`), so the
     * LiteRT-LM engine is never built and its ~3.7 GB download would be a long wait for a file nothing
     * opens. The spec is kept so restoring on-device reading is just adding it back to this list.
     *
     * [YOLO] drops out on the same argument while [ModelConfig.Yolo.BUNDLED_ASSET] is set: Stage 2
     * then maps the bundled asset and never opens the downloaded file, so downloading it would gate
     * this screen on 10 MB nothing reads.
     */
    val ALL: List<ModelSpec>
        get() = if (boardDetectorIsBundled) listOf(PRICE_REGION) else listOf(YOLO, PRICE_REGION)

    /** Private, per-app directory the models are downloaded into (created on demand). */
    fun modelsDir(context: Context): File =
        File(context.applicationContext.filesDir, "models").apply { mkdirs() }

    /** The final on-disk file for [spec] (may not exist yet). */
    fun file(context: Context, spec: ModelSpec): File = File(modelsDir(context), spec.fileName)

    /** True once [spec]'s fully-downloaded file is present (partial `.part` files don't count). */
    fun isDownloaded(context: Context, spec: ModelSpec): Boolean = file(context, spec).length() > 0L

    /** True once every model in [ALL] has been downloaded. */
    fun allDownloaded(context: Context): Boolean = ALL.all { isDownloaded(context, it) }

    /** Absolute path the LiteRT-LM engine loads the Gemma model from. */
    fun gemmaPath(context: Context): String = file(context, GEMMA).path

    /** File the TFLite interpreter memory-maps the YOLO board-detection model from. */
    fun yoloFile(context: Context): File = file(context, YOLO)

    /** File the TFLite interpreter memory-maps the YOLO price-region model from (Stage 3). */
    fun priceRegionFile(context: Context): File = file(context, PRICE_REGION)
}
