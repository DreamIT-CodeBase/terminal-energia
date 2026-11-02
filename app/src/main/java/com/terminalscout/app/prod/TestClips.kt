package com.terminalscout.app.prod

import android.content.Context
import java.io.File

/**
 * The one definition of "this video is a sideloaded test clip".
 *
 * ### Why it is a question at all
 * A replayed clip has to behave like the capture it stands in for in the ways that matter — it gets the
 * location it was originally shot at, and it runs the same six stages — while being kept out of anything
 * that would put dev noise into the fleet's real data. Three separate places need to make that call:
 * where the clip's position comes from ([com.terminalscout.app.prod.upload.TestClipLocations]), which filter
 * branch it takes ([com.terminalscout.app.prod.pipeline.DayNight]), and whether its artifacts are uploaded to
 * the debug container ([com.terminalscout.app.prod.download.DebugUploader]).
 *
 * Each used to answer it by comparing parent directories itself, and two of them disagreed about where
 * the directory even was when external storage is unavailable — one looked in the cache directory, the
 * other in files. On such a device a clip would have been listed but not recognised, which is the kind of
 * split that shows up as one inexplicable run. So the directory and the test is defined once, here.
 *
 * ### The rule
 * A test clip is a file sitting DIRECTLY in [dir]. Not a name pattern: a real recording could be renamed
 * to anything, and the location is the only property a sideloaded clip actually has — `adb push` put it
 * there. Everything else, including every camera capture (which lives in `filesDir/captures/`), is a real
 * capture.
 */
object TestClips {

    private const val DIR = "testclips"

    /**
     * `<external files>/testclips/` — app-private external storage, so `adb push` reaches it without a
     * storage permission, falling back to internal files on a device with no external volume at all.
     */
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)

    /** True when [videoFile] is a sideloaded clip rather than something the camera recorded. */
    fun isTestClip(context: Context, videoFile: File): Boolean =
        videoFile.parentFile?.absolutePath == dir(context).absolutePath
}
