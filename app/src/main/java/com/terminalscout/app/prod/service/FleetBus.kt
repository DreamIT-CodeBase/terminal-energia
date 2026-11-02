package com.terminalscout.app.prod.service

import com.terminalscout.app.prod.capture.TriggerSource
import com.terminalscout.app.prod.pipeline.PipelineEvent
import com.terminalscout.app.prod.config.DeviceConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one-way channel from [FleetService] to whatever is watching it.
 *
 * ### Why this exists
 * The work used to be owned by the screen that displayed it: `FirstFragment` held the queue, ran the
 * drain loop in its view lifecycle scope, and collected the pipeline's flow straight into its own
 * adapters. That is a fine shape for a debug tool and the wrong one for a truck — the loop stopped the
 * moment the screen did, so processing only ever happened with somebody looking at it.
 *
 * The service owns the work now. This object is how a screen — which is optional, and on a deployed
 * truck absent — watches it happen. Traffic is strictly one-way: the bus carries facts *out*. Anything
 * a screen wants to *ask for* goes through [FleetService]'s intents, so there is one door into the work
 * and it is the same door the autonomous triggers use.
 *
 * ### Everything here is replaceable state, not a log
 * The queue and status are [StateFlow]s: a screen opening mid-run gets the current picture immediately
 * and correctly, rather than an empty view waiting for the next event. [pipelineEvents] is the one
 * exception — it is a live feed with no history, because its payloads are decoded bitmaps and keeping
 * a run's worth of them alive for a screen that may never open is exactly the memory pressure this app
 * has been killed for before.
 *
 * ### The observer count is load-bearing
 * [observers] is not a metric. The pipeline decodes a preview bitmap for every frame, crop and canvas
 * it produces — hundreds per run, and pure waste when nothing is going to draw them. [hasObservers]
 * is read by the service each run and switches those emissions off, so a headless truck pays for the
 * work it actually needs. See `PriceBoardPipeline.emitPreviews`.
 */
object FleetBus {

    // ---- Pipeline events (live feed, no replay) ------------------------------------------------ //

    private val _pipelineEvents = MutableSharedFlow<PipelineEvent>(
        replay = 0,
        extraBufferCapacity = PIPELINE_EVENT_BUFFER,
        // A screen that cannot keep up loses thumbnails; it must never slow the pipeline down, and it
        // must never let undelivered bitmaps accumulate. Both were live failures: an unbounded channel
        // let the frame decoder run seconds ahead of the main thread at ~33 MB a frame, and the process
        // was killed for memory.
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Every pipeline event of the run in progress. No replay — a late observer joins mid-stream. */
    val pipelineEvents: SharedFlow<PipelineEvent> = _pipelineEvents.asSharedFlow()

    /** Publishes one pipeline event. Never suspends and never fails; drops under back-pressure. */
    fun emit(event: PipelineEvent) {
        _pipelineEvents.tryEmit(event)
    }

    // ---- Queue (current state) ----------------------------------------------------------------- //

    /**
     * One row of the processing queue as a screen sees it.
     *
     * A flat immutable snapshot rather than the service's own `QueuedVideo`: that class carries a
     * `File`, a mutable status and a `VideoSource`, none of which a renderer has any business holding,
     * and its mutability is what let the old adapter read a status that changed under it mid-draw.
     */
    data class QueueRow(
        val id: Long,
        val name: String,
        /**
         * True for the one clip the pipeline currently has open.
         *
         * Replaced a four-valued `QueueItemStatus`. With the disk as the queue there are only two
         * states a renderer can honestly show: the clip being processed, and the clips waiting. DONE is
         * not a state — a finished clip is deleted — and FAILED is indistinguishable from waiting,
         * because a failed clip stays on disk and is retried.
         */
        val processing: Boolean,
        /**
         * The clip's size on disk, or 0 when the file has gone.
         *
         * Carried on the row rather than measured by whoever draws it: the file belongs to the service
         * and a screen that stats it is a screen reading service-side state, which is the thing this
         * type exists to prevent. It is also what lets the queue report what it is costing without
         * anyone walking the directory.
         */
        val bytes: Long = 0L
    )

    /**
     * The board a recording is committed to, while it is rolling.
     *
     * Published when the recorder actually starts and cleared when the clip finalizes, so its presence
     * means "the camera is rolling for this board" and nothing weaker. The station's position is
     * resolved once, at that moment, rather than being looked up by whoever renders it.
     *
     * [stationId] is null for a manual capture, which has no board — the screen says so rather than
     * showing an empty coordinate.
     */
    data class ActiveCapture(
        val stationId: String?,
        val stationName: String,
        val latitude: Double?,
        val longitude: Double?,
        val trigger: TriggerSource,
        val reason: String,
        val startedAtMs: Long
    )

    private val _activeCapture = MutableStateFlow<ActiveCapture?>(null)

    /** The board currently being recorded, or null when the camera is not rolling. */
    val activeCapture: StateFlow<ActiveCapture?> = _activeCapture.asStateFlow()

    /** Set when the recorder rolls. */
    fun publishActiveCapture(capture: ActiveCapture?) {
        _activeCapture.value = capture
    }

    private val _queue = MutableStateFlow<List<QueueRow>>(emptyList())

    /** The processing queue, oldest first. Replaced wholesale on every change. */
    val queue: StateFlow<List<QueueRow>> = _queue.asStateFlow()

    /** Publishes the queue's current contents. */
    fun publishQueue(rows: List<QueueRow>) {
        _queue.value = rows
    }

    // ---- Status line (current state) ----------------------------------------------------------- //

    /** What the service is doing, in one human sentence. Mirrored into the foreground notification. */
    data class Status(
        val text: String,
        /** True while a clip is in the pipeline. */
        val processing: Boolean = false,
        /** True while the camera is rolling. */
        val recording: Boolean = false
    )

    private val _status = MutableStateFlow(Status("Idle."))

    /** The service's current status. Set at every state change, so a screen never shows a stale one. */
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Publishes a new status. */
    fun publishStatus(status: Status) {
        _status.value = status
    }

    /** Convenience: replace the status text, keeping the current lane flags. */
    fun publishStatus(text: String) {
        _status.value = _status.value.copy(text = text)
    }

    // ---- Completed runs ------------------------------------------------------------------------ //

    private val _runsChanged = MutableStateFlow(0)

    /**
     * Bumped whenever a finished run has been filed in `RunOutputStore`.
     *
     * A counter rather than the runs themselves: the store reads from disk and prunes, so it is the
     * authority, and shipping a parallel copy through here would give a screen two answers to the same
     * question. This just says "ask it again".
     */
    val runsChanged: StateFlow<Int> = _runsChanged.asStateFlow()

    /** Signals that the run store has a new entry. */
    fun notifyRunsChanged() {
        _runsChanged.value = _runsChanged.value + 1
    }

    // ---- Observer accounting ------------------------------------------------------------------- //

    private val observers = AtomicInteger(0)

    /**
     * True while at least one screen is watching.
     *
     * Read by the service to decide whether the pipeline should decode preview bitmaps at all. A
     * deployed truck has no screen and should not pay for hundreds of decodes per run.
     */
    val hasObservers: Boolean get() = observers.get() > 0

    /** Call when a screen starts observing. Balanced by [removeObserver]. */
    fun addObserver() {
        observers.incrementAndGet()
    }

    /** Call when a screen stops observing. Floors at zero so an unbalanced call cannot go negative. */
    fun removeObserver() {
        observers.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /**
     * How many pipeline events may sit undelivered before the oldest are dropped.
     *
     * Small on purpose — several of them carry a decoded bitmap. Big enough that a burst of stage
     * events never costs a screen the shape of the timeline.
     */
    private val PIPELINE_EVENT_BUFFER: Int get() = DeviceConfig.int("capture.service.event_buffer", 16)
}
