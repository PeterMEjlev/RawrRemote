package com.rawr.ccapi

import com.rawr.ccapi.net.CcapiClient

/**
 * Holds the single connected camera client for the process (MVP: one camera).
 * The ViewModel sets it on connect; the download service reads it.
 */
object CameraSession {
    @Volatile
    var client: CcapiClient? = null

    @Volatile
    var deviceName: String? = null

    fun clear() {
        client = null
        deviceName = null
    }
}
