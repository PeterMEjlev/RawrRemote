package com.rawr.ccapi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rawr.ccapi.CameraSession
import com.rawr.ccapi.net.CameraSetting
import com.rawr.ccapi.net.CcapiEndpoints
import com.rawr.ccapi.net.CcapiException
import com.rawr.ccapi.net.CcapiClient
import com.rawr.ccapi.net.ImageQualityOption
import com.rawr.ccapi.net.ImageQualityState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Drives the "Control" page: polls the camera's live view, fires the shutter,
 * exposes the ISO / shutter / aperture settings, and does touch focus — all over
 * CCAPI via the shared connected client in [CameraSession]. Fully driven by the
 * screen's presence + lifecycle (start/stop), so live view only runs while the
 * page is actually visible.
 */
class ControlViewModel : ViewModel() {

    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    // Live-view coordinate size, for mapping a tap to camera AF coordinates.
    var frameWidth by mutableStateOf(0)
        private set
    var frameHeight by mutableStateOf(0)
        private set

    var active by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var capturing by mutableStateOf(false)
        private set
    var shutterMessage by mutableStateOf<String?>(null)
        private set

    // Exposure settings (null until loaded / when the camera doesn't report them).
    var iso by mutableStateOf<CameraSetting?>(null)
        private set
    var tv by mutableStateOf<CameraSetting?>(null)
        private set
    var av by mutableStateOf<CameraSetting?>(null)
        private set

    // "More" submenu settings (loaded lazily alongside the exposure settings).
    var driveMode by mutableStateOf<CameraSetting?>(null)
        private set
    var afOperation by mutableStateOf<CameraSetting?>(null)
        private set
    var afMethod by mutableStateOf<CameraSetting?>(null)
        private set
    var imageQuality by mutableStateOf<ImageQualityState?>(null)
        private set
    var moreSettings by mutableStateOf<Map<String, CameraSetting>>(emptyMap())
        private set

    /** Current AF method token (e.g. "spotAF"), drives the live-view reticle shape. */
    val afMethodValue: String? get() = afMethod?.value

    val connected: Boolean get() = CameraSession.client != null

    private var loopJob: Job? = null
    private var focusJob: Job? = null
    private var pendingFocus: FocusTarget? = null
    private var stopJob: Job? = null
    private var liveViewGeneration = 0
    private val settingGenerations = mutableMapOf<String, Int>()

    private data class FocusTarget(val x: Int, val y: Int)

    /** Start live view + frame polling, and load exposure settings. Idempotent. */
    fun start() {
        if (active) return
        val client = CameraSession.client ?: return
        val generation = ++liveViewGeneration
        stopJob?.cancel()
        stopJob = null
        active = true
        error = null
        loopJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.startLiveView() }
                launch { loadSettings() } // concurrent — must not delay frames
                while (isActive) {
                    val bytes = withContext(Dispatchers.IO) { client.getLiveViewFrame() }
                    if (bytes != null) {
                        val decoded = withContext(Dispatchers.Default) { decodeSampledImageWithSize(bytes, reqWidth = 1280) }
                        if (decoded != null) {
                            frame = decoded.image
                            frameWidth = decoded.width
                            frameHeight = decoded.height
                            error = null
                        }
                    }
                    delay(33) // ~30 fps cap; also yields between polls
                }
            } catch (e: CancellationException) {
                throw e // normal stop — don't surface as an error
            } catch (e: CcapiException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Live view failed"
            } finally {
                if (liveViewGeneration == generation) active = false
            }
        }
    }

    /** Stop polling + live view. Idempotent. */
    fun stop() {
        liveViewGeneration++
        loopJob?.cancel()
        loopJob = null
        focusJob?.cancel()
        focusJob = null
        pendingFocus = null
        active = false
        val client = CameraSession.client ?: return
        val generation = liveViewGeneration
        stopJob?.cancel()
        stopJob = viewModelScope.launch {
            delay(150)
            if (liveViewGeneration == generation) {
                withContext(Dispatchers.IO) { runCatching { client.stopLiveView() } }
            }
        }
    }

    fun takePhoto() {
        if (capturing) return
        val client = CameraSession.client ?: return
        capturing = true
        shutterMessage = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.pressShutter(af = true) }
                shutterMessage = "Photo taken"
            } catch (e: CcapiException) {
                shutterMessage = e.message ?: "Shot failed"
            } catch (e: Exception) {
                shutterMessage = e.message ?: "Shot failed"
            } finally {
                capturing = false
            }
        }
    }

    // -- exposure settings ------------------------------------------------

    private suspend fun loadSettings() {
        val client = CameraSession.client ?: return
        iso = fetch(CcapiEndpoints.SETTING_ISO) ?: iso
        tv = fetch(CcapiEndpoints.SETTING_TV) ?: tv
        av = fetch(CcapiEndpoints.SETTING_AV) ?: av
        // The "More" settings are best-effort: a body that doesn't report one
        // just leaves that row hidden (null), never failing live view.
        val loadedMoreSettings = fetchMoreSettings()
        if (loadedMoreSettings.isNotEmpty()) {
            moreSettings = moreSettings + loadedMoreSettings
            syncCuratedMoreSettings()
        }
        imageQuality = fetchImageQuality() ?: imageQuality
    }

    private suspend fun fetch(name: String): CameraSetting? = withContext(Dispatchers.IO) {
        runCatching { CameraSession.client?.getSetting(name) }.getOrNull()
    }

    private suspend fun fetchMoreSettings(): Map<String, CameraSetting> = withContext(Dispatchers.IO) {
        runCatching { CameraSession.client?.getMoreSettings().orEmpty() }.getOrDefault(emptyMap())
    }

    private suspend fun fetchImageQuality(): ImageQualityState? = withContext(Dispatchers.IO) {
        runCatching { CameraSession.client?.getImageQuality() }.getOrNull()
    }

    private fun syncCuratedMoreSettings() {
        driveMode = moreSettings[CcapiEndpoints.SETTING_DRIVE_MODE] ?: driveMode
        afOperation = moreSettings[CcapiEndpoints.SETTING_AF_OPERATION] ?: afOperation
        afMethod = moreSettings[CcapiEndpoints.SETTING_AF_METHOD] ?: afMethod
    }

    fun setIso(value: String) = applySetting(CcapiEndpoints.SETTING_ISO, value, { iso }, { iso = it })
    fun setTv(value: String) = applySetting(CcapiEndpoints.SETTING_TV, value, { tv }, { tv = it })
    fun setAv(value: String) = applySetting(CcapiEndpoints.SETTING_AV, value, { av }, { av = it })

    fun setDriveMode(value: String) = setMoreSetting(CcapiEndpoints.SETTING_DRIVE_MODE, value)
    fun setAfOperation(value: String) = setMoreSetting(CcapiEndpoints.SETTING_AF_OPERATION, value)
    fun setAfMethod(value: String) = setMoreSetting(CcapiEndpoints.SETTING_AF_METHOD, value)

    fun setMoreSetting(name: String, value: String) =
        applySetting(
            name = name,
            value = value,
            get = { moreSettings[name] },
            set = { setting ->
                moreSettings = if (setting == null) moreSettings - name else moreSettings + (name to setting)
                syncCuratedMoreSettings()
            },
        )

    /** Apply an image-quality combination, then re-read (the camera may clamp it). */
    fun setImageQuality(option: ImageQualityOption) {
        val client = CameraSession.client ?: return
        val current = imageQuality ?: return
        if (current.currentLabel == option.label) return
        val generation = (settingGenerations[CcapiEndpoints.SETTING_STILL_IMAGE_QUALITY] ?: 0) + 1
        settingGenerations[CcapiEndpoints.SETTING_STILL_IMAGE_QUALITY] = generation
        imageQuality = current.copy(currentLabel = option.label) // optimistic
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.putImageQuality(option) }
                if (settingGenerations[CcapiEndpoints.SETTING_STILL_IMAGE_QUALITY] == generation) {
                    fetchImageQuality()?.let { imageQuality = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (settingGenerations[CcapiEndpoints.SETTING_STILL_IMAGE_QUALITY] == generation) {
                    shutterMessage = e.message ?: "Couldn't change image quality"
                    imageQuality = current // revert
                }
            }
        }
    }

    fun stepIso(steps: Int) = stepExposure(iso, steps, ::setIso)
    fun stepTv(steps: Int) = stepExposure(tv, steps, ::setTv)
    fun stepAv(steps: Int) = stepExposure(av, steps, ::setAv)

    private fun stepExposure(setting: CameraSetting?, steps: Int, setValue: (String) -> Unit) {
        if (steps == 0) return
        val options = setting?.options ?: return
        if (options.size < 2) return
        val currentIndex = options.indexOf(setting.value).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + steps).coerceIn(0, options.lastIndex)
        if (nextIndex != currentIndex) setValue(options[nextIndex])
    }

    private fun applySetting(
        name: String,
        value: String,
        get: () -> CameraSetting?,
        set: (CameraSetting?) -> Unit,
    ) {
        val client = CameraSession.client ?: return
        val current = get() ?: return
        if (current.value == value) return
        val generation = (settingGenerations[name] ?: 0) + 1
        settingGenerations[name] = generation
        set(current.copy(value = value)) // optimistic
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.putSetting(name, value) }
                // Re-read: the camera may clamp the value or change the options.
                if (settingGenerations[name] == generation) fetch(name)?.let(set)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (settingGenerations[name] == generation) {
                    shutterMessage = e.message ?: "Couldn't change $name"
                    set(current) // revert
                }
            }
        }
    }

    // -- touch focus ------------------------------------------------------

    /** Focus at a point given as fractions (0..1) of the live-view image. */
    fun focusAt(fx: Float, fy: Float) {
        val client = CameraSession.client ?: return
        // The tap is normalized from the displayed live view, but Canon's
        // afframeposition endpoint wants the larger AF/sensor coordinate grid.
        val x = (fx.coerceIn(0f, 1f) * (CcapiEndpoints.LIVEVIEW_AFFRAME_COORD_WIDTH - 1)).roundToInt()
        val y = (fy.coerceIn(0f, 1f) * (CcapiEndpoints.LIVEVIEW_AFFRAME_COORD_HEIGHT - 1)).roundToInt()
        pendingFocus = FocusTarget(x, y)
        if (focusJob?.isActive == true) return
        focusJob = viewModelScope.launch {
            try {
                while (isActive) {
                    val target = pendingFocus ?: break
                    pendingFocus = null
                    runFocusCycle(client, target)
                    if (pendingFocus != null) delay(250)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                focusJob = null
            }
        }
    }

    private suspend fun runFocusCycle(client: CcapiClient, target: FocusTarget) {
        var afStarted = false
        try {
            withContext(Dispatchers.IO) {
                client.setAfFramePosition(target.x, target.y)
                client.driveAf(true)
                afStarted = true
            }
            delay(700)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CcapiException) {
            shutterMessage = e.message ?: "Touch focus failed"
        } catch (e: Exception) {
            shutterMessage = e.message ?: "Touch focus failed"
        } finally {
            if (afStarted) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { client.driveAf(false) } }
                delay(150)
            }
        }
    }

    override fun onCleared() {
        // Best-effort: cancel the loop. (The stop POST may not run if the scope
        // is already torn down, but the camera times live view out on its own.)
        loopJob?.cancel()
        focusJob?.cancel()
        pendingFocus = null
        stopJob?.cancel()
    }
}
