package com.terminalscout.app.prod.ui

/**
 * A screen that resolves a problem.
 *
 * Only conditions the operator can actually act on get one. There is deliberately no "contact support"
 * member: a fix is a place to go, and inventing a destination for a fault nobody on the device can
 * clear would make every unfixable error look actionable.
 */
enum class ErrorFix {
    /** The app's own permission page. */
    APP_PERMISSIONS,

    /** The battery-optimisation exemption dialog. */
    BATTERY_OPTIMISATION,

    /** The "display over other apps" settings screen. */
    OVERLAY_PERMISSION,

    /** System location settings — for location services being switched off entirely. */
    LOCATION_SETTINGS,

    /** This app's DEVICE INFO screen. */
    DEVICE_INFO,

    /** Device storage settings, so space can be freed. */
    STORAGE_SETTINGS,

    /** The per-app "Alarms & reminders" screen. */
    EXACT_ALARMS
}

/**
 * A common error and the screen that fixes it.
 *
 * @param key      stable identity, persisted in the log. Never rename one once shipped; reword
 *                 [message] freely.
 * @param message  what the operator reads.
 * @param source   which part of the app raises it.
 * @param fix      where tapping the row goes.
 */
data class KnownError(
    val key: String,
    val message: String,
    val source: String,
    val fix: ErrorFix
)

/**
 * Fixes for the errors that have one.
 *
 * ### This is a lookup, not a gate
 * It lists **common errors an operator can resolve**, so tapping the row opens the right screen. It is
 * emphatically not a list of everything the app can report: any error can be raised with
 * [ErrorLog.showError] taking a key and message directly, and it appears on the errors screen exactly
 * like the ones in here. Missing from this table means "no shortcut to a fix", never "cannot be shown".
 *
 * An earlier version got this backwards — `showError` only accepted a [KnownError], so nothing outside
 * this file could be reported at all, and seven entries existed purely to route to a support mailbox.
 * Both are gone.
 *
 * ### Adding one
 * Add an entry here only when there is a real screen that resolves it. Everything else is reported at
 * its own call site with its own constant, which is where the knowledge of that failure already lives.
 */
object AppErrors {

    val PERMISSIONS = KnownError(
        key = "permissions",
        message = "Camera and location permissions are needed before anything can be captured.",
        source = "Setup",
        fix = ErrorFix.APP_PERMISSIONS
    )

    val BACKGROUND_LOCATION = KnownError(
        key = "background_location",
        message = "Set location to \"Allow all the time\" — auto capture stops when the screen is off.",
        source = "Setup",
        fix = ErrorFix.APP_PERMISSIONS
    )

    val BATTERY_OPTIMISED = KnownError(
        key = "battery_optimised",
        message = "Battery optimisation is on. Allow this app to run unrestricted, or capture will be throttled.",
        source = "Setup",
        fix = ErrorFix.BATTERY_OPTIMISATION
    )

    val NO_OVERLAY = KnownError(
        key = "no_overlay",
        message = "Allow \"display over other apps\", or capture cannot recover on its own after a restart.",
        source = "Setup",
        fix = ErrorFix.OVERLAY_PERMISSION
    )

    val GPS_OFF = KnownError(
        key = "gps_off",
        message = "Location services are switched off. Nothing can be captured until they are on.",
        source = "Setup",
        fix = ErrorFix.LOCATION_SETTINGS
    )

    val NO_DEVICE_DETAILS = KnownError(
        key = "no_device_details",
        message = "Enter the device info. Capture is held until it is set.",
        source = "Setup",
        fix = ErrorFix.DEVICE_INFO
    )

    val NO_CAMERA_PERMISSION = KnownError(
        key = "no_camera_permission",
        message = "Camera permission is not granted, so nothing can be recorded.",
        source = "Capture",
        fix = ErrorFix.APP_PERMISSIONS
    )

    val LOW_STORAGE = KnownError(
        key = "low_storage",
        message = "Not enough free storage to record. Free up space before the next forecourt.",
        source = "Capture",
        fix = ErrorFix.STORAGE_SETTINGS
    )

    val NO_EXACT_ALARMS = KnownError(
        key = "no_exact_alarms",
        message = "Allow \"alarms & reminders\", or the app takes minutes rather than seconds to restart itself.",
        source = "Setup",
        fix = ErrorFix.EXACT_ALARMS
    )

    /** Every error with a known fix. */
    val ALL: List<KnownError> = listOf(
        PERMISSIONS, BACKGROUND_LOCATION, BATTERY_OPTIMISED, NO_OVERLAY,
        GPS_OFF, NO_DEVICE_DETAILS, NO_CAMERA_PERMISSION, LOW_STORAGE, NO_EXACT_ALARMS
    )

    private val byKey: Map<String, KnownError> = ALL.associateBy { it.key }

    /**
     * The fix for a logged key, or null when there is none.
     *
     * Null is the ordinary answer for most of the log, not an error condition: a failed map fetch or a
     * camera that would not bind is reported and read, and there is no screen that resolves it.
     */
    fun byKey(key: String): KnownError? = byKey[key]
}
