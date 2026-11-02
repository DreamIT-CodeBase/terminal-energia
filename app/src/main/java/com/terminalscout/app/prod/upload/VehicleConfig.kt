package com.terminalscout.app.prod.upload

/**
 * User-supplied identity the pipeline can't infer, merged into every uploaded payload. Edited via
 * the vehicle/device form and persisted in [UserStore].
 */
data class VehicleConfig(
    val deviceId: String,
    val vehicleId: String
)
