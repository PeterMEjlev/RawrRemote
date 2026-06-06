@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.rawr.ccapi.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rawr.ccapi.R
import com.rawr.ccapi.net.CameraSetting
import com.rawr.ccapi.net.CcapiEndpoints
import com.rawr.ccapi.net.ImageQualityOption
import com.rawr.ccapi.net.ImageQualityState
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Which flat ({"value","ability":[..]}) setting a selector sheet is editing.
 * Covers the exposure pills (ISO/TV/AV) and the three flat "More" settings.
 * Image quality is structured and handled separately (see [ImageQualitySheet]).
 */
private enum class SettingKind(val label: String) {
    ISO("ISO"),
    TV("Shutter"),
    AV("Aperture"),
    DRIVE_MODE("Drive mode"),
    AF_OPERATION("AF operation"),
    AF_METHOD("AF method"),
}

private val MORE_SETTING_LABELS = mapOf(
    CcapiEndpoints.SETTING_DRIVE_MODE to "Drive mode",
    CcapiEndpoints.SETTING_AF_OPERATION to "AF operation",
    CcapiEndpoints.SETTING_AF_METHOD to "AF method",
    "wb" to "White balance",
    "colortemperature" to "Color temperature",
    "metering" to "Metering mode",
    "stillimageaspectratio" to "Still photo aspect ratio",
    "flash" to "Flash",
    "aeb" to "AEB",
    "wbshift" to "White balance correction",
    "wbbracket" to "White balance bracketing",
    "colorspace" to "Color space",
    "picturestyle" to "Picture Style",
    "shuttermode" to "Shutter mode",
    "trackingsetting" to "Tracking setting",
    "hdr" to "HDR Mode",
    "antiflickershoot" to "Anti-Flicker Shooting",
    "focusbracketing" to "Focus bracketing",
    "shootingmodedial" to "Shooting mode",
    "exposure" to "Exposure compensation",
    "moviequality" to "Movie recording quality",
    "soundrecording" to "Sound recording",
    "highframerate" to "High Frame Rate",
)

private fun moreSettingLabel(name: String): String =
    MORE_SETTING_LABELS[name] ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun moreSettingSortIndex(name: String): Int {
    val index = CcapiEndpoints.MORE_SETTING_CANDIDATES.indexOf(name)
    return if (index >= 0) index else Int.MAX_VALUE
}

private fun settingValueLabel(settingName: String?, value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return trimmed
    val key = trimmed.lowercase().replace("-", "_").replace(" ", "_")
    val compact = trimmed.lowercase().filter { it.isLetterOrDigit() || it == '+' }
    return when (settingName) {
        CcapiEndpoints.SETTING_DRIVE_MODE -> driveModeValueLabel(key, compact, trimmed)
        CcapiEndpoints.SETTING_AF_OPERATION -> afOperationValueLabel(compact, trimmed)
        CcapiEndpoints.SETTING_AF_METHOD -> afMethodValueLabel(compact, trimmed)
        "wb" -> whiteBalanceValueLabel(key, compact, trimmed)
        "metering" -> meteringValueLabel(key, compact, trimmed)
        "shuttermode" -> shutterModeValueLabel(compact, trimmed)
        "colorspace" -> colorSpaceValueLabel(compact, trimmed)
        "picturestyle" -> pictureStyleValueLabel(key, compact, trimmed)
        "colortemperature" -> colorTemperatureValueLabel(trimmed)
        "shootingmodedial" -> shootingModeValueLabel(compact, trimmed)
        "exposure", "aeb", "wbbracket" -> trimmed.replace('_', ' ')
        "trackingsetting", "hdr", "antiflickershoot", "focusbracketing", "flash", "highframerate" ->
            enableDisableValueLabel(compact, trimmed)
        else -> prettyTokenLabel(trimmed)
    }
}

private fun driveModeValueLabel(key: String, compact: String, fallback: String): String = when {
    compact == "single" -> "Single shooting"
    compact == "lowspeed" -> "Low-speed continuous shooting"
    compact == "highspeed" -> "High-speed continuous shooting"
    compact in setOf("contsuperhi", "superhighspeed", "highspeedcontinuous+", "highspeedcontinuousplus") ->
        "High-speed continuous shooting +"
    key in setOf("self_10sec", "self_10_sec") -> "Self-timer: 10 sec./remote control"
    key in setOf("self_2sec", "self_2_sec") -> "Self-timer: 2 sec./remote control"
    key == "self_continuous" -> "Self-timer: Continuous"
    else -> prettyTokenLabel(fallback)
}

private fun afOperationValueLabel(compact: String, fallback: String): String = when (compact) {
    "oneshot", "oneshotaf" -> "One-Shot AF"
    "servo", "servoaf" -> "Servo AF"
    else -> prettyTokenLabel(fallback)
}

private fun afMethodValueLabel(compact: String, fallback: String): String = when (compact) {
    "face+tracking", "tracking", "facetracking" -> "Face+Tracking"
    "spot", "spotaf" -> "Spot AF"
    "1point", "1pointaf" -> "1-point AF"
    "expand", "expandafarea", "expandcross" -> "Expand AF area"
    "expandafareaaround", "expandaround" -> "Expand AF area: Around"
    "zone", "zoneaf" -> "Zone AF"
    "largezone", "largezoneaf" -> "Large Zone AF"
    "largezonevertical", "largezoneafvertical" -> "Large Zone AF: Vertical"
    "largezonehorizontal", "largezoneafhorizontal" -> "Large Zone AF: Horizontal"
    "flexiblezone1", "flexiblezoneaf1" -> "Flexible Zone AF 1"
    "flexiblezone2", "flexiblezoneaf2" -> "Flexible Zone AF 2"
    "flexiblezone3", "flexiblezoneaf3" -> "Flexible Zone AF 3"
    "wholearea", "wholeareaaf" -> "Whole area AF"
    else -> prettyTokenLabel(fallback)
}

private fun whiteBalanceValueLabel(key: String, compact: String, fallback: String): String = when {
    compact in setOf("auto", "awb", "autowhitebalance") -> "Auto (Ambience priority)"
    compact in setOf("awbwhite", "autowhitepriority", "whitepriority") -> "Auto (White priority)"
    compact == "daylight" -> "Daylight"
    compact == "shade" -> "Shade"
    compact == "cloudy" -> "Cloudy"
    compact == "tungsten" -> "Tungsten light"
    compact in setOf("whitefluorescent", "fluorescent") -> "White fluorescent light"
    compact == "flash" -> "Flash"
    compact == "custom" -> "Custom"
    key in setOf("colortemp", "color_temperature") || compact == "colortemperature" -> "Color temperature"
    else -> prettyTokenLabel(fallback)
}

private fun meteringValueLabel(key: String, compact: String, fallback: String): String = when {
    compact == "evaluative" -> "Evaluative metering"
    compact == "partial" -> "Partial metering"
    compact == "spot" -> "Spot metering"
    key == "center_weighted_average" || compact == "centerweightedaverage" -> "Center-weighted average"
    else -> prettyTokenLabel(fallback)
}

private fun shutterModeValueLabel(compact: String, fallback: String): String = when (compact) {
    "mechanical" -> "Mechanical"
    "elec1stcurtain", "electronic1stcurtain", "electronicfirstcurtain" -> "Elec. 1st-curtain"
    "electronic" -> "Electronic"
    else -> prettyTokenLabel(fallback)
}

private fun colorSpaceValueLabel(compact: String, fallback: String): String = when (compact) {
    "srgb" -> "sRGB"
    "adobergb" -> "Adobe RGB"
    else -> prettyTokenLabel(fallback)
}

private fun pictureStyleValueLabel(key: String, compact: String, fallback: String): String = when {
    compact == "auto" -> "Auto"
    compact == "standard" -> "Standard"
    compact == "portrait" -> "Portrait"
    compact == "landscape" -> "Landscape"
    compact == "finedetail" -> "Fine Detail"
    compact == "neutral" -> "Neutral"
    compact == "faithful" -> "Faithful"
    compact == "monochrome" -> "Monochrome"
    key == "userdef1" || key == "user_def_1" -> "User Def. 1"
    key == "userdef2" || key == "user_def_2" -> "User Def. 2"
    key == "userdef3" || key == "user_def_3" -> "User Def. 3"
    else -> prettyTokenLabel(fallback)
}

private fun colorTemperatureValueLabel(value: String): String =
    if (value.all(Char::isDigit)) "${value}K" else prettyTokenLabel(value)

private fun shootingModeValueLabel(compact: String, fallback: String): String = when (compact) {
    "a+" -> "A+"
    "fv" -> "Fv"
    "p" -> "P"
    "tv" -> "Tv"
    "av" -> "Av"
    "m" -> "M"
    "bulb" -> "BULB"
    "c1" -> "C1"
    "c2" -> "C2"
    "c3" -> "C3"
    else -> prettyTokenLabel(fallback)
}

private fun enableDisableValueLabel(compact: String, fallback: String): String = when (compact) {
    "enable", "enabled" -> "Enable"
    "disable", "disabled" -> "Disable"
    "on" -> "On"
    "off" -> "Off"
    else -> prettyTokenLabel(fallback)
}

private fun prettyTokenLabel(value: String): String {
    val cleaned = value.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    if (cleaned.isBlank()) return cleaned
    return cleaned.replaceFirstChar { it.uppercase() }
}
@Composable
fun ControlScreen(vm: ControlViewModel, connected: Boolean) {
    // Live view should run only while this page is actually on-screen and the
    // app is foregrounded, so it follows both presence and the lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(connected, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (connected) vm.start()
                Lifecycle.Event.ON_STOP -> vm.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (connected) vm.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stop()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Box(Modifier.fillMaxSize()) {
                if (!connected) {
                    CenteredMessage("Connect to the camera on the Import tab first.")
                } else {
                    ControlContent(vm, landscape = true)
                }
            }
        } else {
            Scaffold(topBar = { TopAppBar(title = { Text("Control") }) }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    if (!connected) {
                        CenteredMessage("Connect to the camera on the Import tab first.")
                    } else {
                        ControlContent(vm, landscape = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlContent(vm: ControlViewModel, landscape: Boolean) {
    // The flat picker (ISO/TV/AV + generic flat "More" settings), the "More"
    // submenu, and the structured image-quality picker each have their own state.
    var sheet by remember { mutableStateOf<SettingKind?>(null) }
    var moreSetting by remember { mutableStateOf<String?>(null) }
    var showMore by remember { mutableStateOf(false) }
    var showImageQuality by remember { mutableStateOf(false) }

    if (landscape) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            LiveViewport(vm, Modifier.weight(1f).fillMaxHeight())
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxHeight().width(220.dp)) {
                LandscapeControls(vm, onOpenSheet = { sheet = it }, onOpenMore = { showMore = true })
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LiveViewport(vm, Modifier.weight(1f).fillMaxWidth())

            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    vm.shutterMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ExposureSettingsRow(vm, onOpenSheet = { sheet = it })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        ShutterButton(vm.capturing) { vm.takePhoto() }
                        MoreButton { showMore = true }
                    }
                }
            }
        }
    }

    // Flat setting picker (shared by the exposure pills and the flat More rows).
    sheet?.let { kind ->
        SettingSheet(
            title = kind.label,
            setting = settingFor(vm, kind),
            settingName = settingNameFor(kind),
            useAfMethodGrid = kind == SettingKind.AF_METHOD,
            onPick = { value ->
                applyFlatSetting(vm, kind, value)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
    }

    moreSetting?.let { name ->
        SettingSheet(
            title = moreSettingLabel(name),
            setting = vm.moreSettings[name],
            settingName = name,
            useAfMethodGrid = name == CcapiEndpoints.SETTING_AF_METHOD,
            onPick = { value ->
                vm.setMoreSetting(name, value)
                moreSetting = null
            },
            onDismiss = { moreSetting = null },
        )
    }

    if (showMore) {
        MoreSettingsSheet(
            vm = vm,
            onPickFlat = { name -> showMore = false; moreSetting = name },
            onPickImageQuality = { showMore = false; showImageQuality = true },
            onDismiss = { showMore = false },
        )
    }

    if (showImageQuality) {
        ImageQualitySheet(
            state = vm.imageQuality,
            onPick = { option -> vm.setImageQuality(option); showImageQuality = false },
            onDismiss = { showImageQuality = false },
        )
    }
}

@Composable
private fun LandscapeControls(
    vm: ControlViewModel,
    onOpenSheet: (SettingKind) -> Unit,
    onOpenMore: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Control", style = MaterialTheme.typography.titleMedium)
        vm.shutterMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ExposureSettingsColumn(vm, onOpenSheet)
        MoreButton(Modifier.fillMaxWidth(), onClick = onOpenMore)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ShutterButton(vm.capturing) { vm.takePhoto() }
        }
    }
}

@Composable
private fun ExposureSettingsRow(vm: ControlViewModel, onOpenSheet: (SettingKind) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingPill(
            "ISO",
            vm.iso,
            Modifier.weight(1f),
            onClick = { onOpenSheet(SettingKind.ISO) },
            onStep = vm::stepIso,
        )
        SettingPill(
            "SHUTTER",
            vm.tv,
            Modifier.weight(1f),
            onClick = { onOpenSheet(SettingKind.TV) },
            onStep = vm::stepTv,
        )
        SettingPill(
            "APERTURE",
            vm.av,
            Modifier.weight(1f),
            onClick = { onOpenSheet(SettingKind.AV) },
            onStep = vm::stepAv,
        )
    }
}

@Composable
private fun ExposureSettingsColumn(vm: ControlViewModel, onOpenSheet: (SettingKind) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingPill(
            "ISO",
            vm.iso,
            Modifier.fillMaxWidth(),
            onClick = { onOpenSheet(SettingKind.ISO) },
            onStep = vm::stepIso,
        )
        SettingPill(
            "SHUTTER",
            vm.tv,
            Modifier.fillMaxWidth(),
            onClick = { onOpenSheet(SettingKind.TV) },
            onStep = vm::stepTv,
        )
        SettingPill(
            "APERTURE",
            vm.av,
            Modifier.fillMaxWidth(),
            onClick = { onOpenSheet(SettingKind.AV) },
            onStep = vm::stepAv,
        )
    }
}

// --- live view + touch focus -----------------------------------------------

@Composable
private fun LiveViewport(vm: ControlViewModel, modifier: Modifier) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var reticle by remember { mutableStateOf<Offset?>(null) }

    fun focusFromTap(tap: Offset) {
        val fw = vm.frameWidth.toFloat()
        val fh = vm.frameHeight.toFloat()
        val bw = boxSize.width.toFloat()
        val bh = boxSize.height.toFloat()
        if (fw <= 0 || fh <= 0 || bw <= 0 || bh <= 0) return

        val scale = minOf(bw / fw, bh / fh)
        val dispW = fw * scale
        val dispH = fh * scale
        val offX = (bw - dispW) / 2f
        val offY = (bh - dispH) / 2f
        val ix = tap.x - offX
        val iy = tap.y - offY
        if (ix in 0f..dispW && iy in 0f..dispH) {
            reticle = tap
            vm.focusAt(ix / dispW, iy / dispH)
        }
    }

    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(vm.frameWidth, vm.frameHeight, boxSize) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastPosition = down.position
                    var totalDx = 0f
                    var totalDy = 0f
                    var multiTouch = false

                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) multiTouch = true
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: continue
                        lastPosition = change.position
                        totalDx += change.position.x - change.previousPosition.x
                        totalDy += change.position.y - change.previousPosition.y
                    } while (event.changes.any { it.pressed })

                    // Map a true tap to the letterboxed image rect, then ask
                    // the camera to focus there. Small finger drift is fine.
                    if (!multiTouch && abs(totalDx) <= slop && abs(totalDy) <= slop) {
                        focusFromTap(lastPosition)
                    }
                }
            },
    ) {
        val f = vm.frame
        when {
            f != null -> Image(
                bitmap = f,
                contentDescription = "Live view",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            vm.error != null -> CenteredMessage(vm.error!!, "Retry") { vm.start() }
            else -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text("Starting live view…", color = Color.White)
            }
        }

        reticle?.let { pos ->
            FocusReticle(afMethod = vm.afMethodValue, center = pos)
            LaunchedEffect(pos) {
                delay(900)
                if (reticle == pos) reticle = null
            }
        }
    }
}

/**
 * The set of AF methods we can draw a glyph for, derived from `afmethod`.
 * Used both by the live-view reticle and the icon-based AF-method picker.
 * Ordered to roughly match Canon's on-camera menu progression.
 */
private enum class AfFrameShape {
    FACE, SPOT, POINT, EXPAND, EXPAND_AROUND, ZONE, LARGE_ZONE, LARGE_ZONE_VERTICAL
}

/**
 * Map a CCAPI `afmethod` token to a frame shape. Matches loosely — lowercased
 * with spaces/hyphens stripped — because the exact string varies across bodies
 * and firmware (e.g. "1-pointAF", "Spot AF", "expandAFareaaround",
 * "face+tracking", "largezoneafvertical"). The R5 exposes both a horizontal and
 * a vertical Large Zone, so those are kept distinct. Anything unrecognised
 * (incl. null) falls back to the neutral 1-point box.
 */
private fun afFrameShapeOf(afMethod: String?): AfFrameShape {
    val key = afMethod?.lowercase()?.replace(Regex("[\\s_-]"), "") ?: return AfFrameShape.POINT
    val largeZone = ("large" in key || "wide" in key) && "zone" in key
    return when {
        "face" in key || "track" in key || "subject" in key -> AfFrameShape.FACE
        "spot" in key -> AfFrameShape.SPOT
        "around" in key -> AfFrameShape.EXPAND_AROUND
        "expand" in key -> AfFrameShape.EXPAND
        // Large Zone variants must be tested before the generic "zone" match,
        // and the vertical one before the horizontal default.
        largeZone && ("vertical" in key || "vert" in key) -> AfFrameShape.LARGE_ZONE_VERTICAL
        largeZone -> AfFrameShape.LARGE_ZONE
        "zone" in key || "wholearea" in key -> AfFrameShape.ZONE
        else -> AfFrameShape.POINT // 1-point and any unknown method
    }
}

/**
 * Draws the focus reticle shown when the user taps the live view to set the AF
 * area, centred on [center] (a position within the live-view Box). It uses the
 * very same AF-method glyphs as the picker ([afMethodDrawable]) so the on-screen
 * frame matches the menu, drawn in white. The glyph box is sized per shape —
 * compact for point/spot methods, wider for the zone frames — and aspect ratio
 * is preserved so the (non-square) zone glyphs aren't distorted.
 */
@Composable
private fun FocusReticle(afMethod: String?, center: Offset) {
    val shape = afFrameShapeOf(afMethod)
    val sizeDp = when (shape) {
        AfFrameShape.SPOT, AfFrameShape.POINT -> 64.dp
        AfFrameShape.FACE, AfFrameShape.EXPAND, AfFrameShape.EXPAND_AROUND -> 88.dp
        AfFrameShape.ZONE -> 132.dp
        AfFrameShape.LARGE_ZONE, AfFrameShape.LARGE_ZONE_VERTICAL -> 168.dp
    }
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }
    Image(
        painter = painterResource(afMethodDrawable(afMethod)),
        contentDescription = null,
        colorFilter = ColorFilter.tint(Color.White),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .offset { IntOffset((center.x - sizePx / 2f).toInt(), (center.y - sizePx / 2f).toInt()) }
            .size(sizeDp),
    )
}

// --- controls bar ----------------------------------------------------------

@Composable
private fun SettingPill(
    label: String,
    setting: CameraSetting?,
    modifier: Modifier,
    onClick: () -> Unit,
    onStep: (Int) -> Unit,
) {
    val value = setting?.value?.takeIf { it.isNotBlank() } ?: "—"
    val enabled = setting?.adjustable == true
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.settingValueGesture(enabled, onClick, onStep),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

private fun Modifier.settingValueGesture(
    enabled: Boolean,
    onClick: () -> Unit,
    onStep: (Int) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(enabled) {
        val slop = viewConfiguration.touchSlop
        val stepPx = 34.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalDy = 0f
            var stepAccumulator = 0f
            var dragging = false

            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: event.changes.firstOrNull()
                    ?: continue
                if (!change.pressed) break

                val dy = change.position.y - change.previousPosition.y
                totalDy += dy
                if (!dragging && abs(totalDy) >= slop) {
                    dragging = true
                    stepAccumulator = totalDy
                } else if (dragging) {
                    stepAccumulator += dy
                }

                if (dragging) {
                    val steps = (stepAccumulator / stepPx).toInt()
                    if (steps != 0) {
                        // Finger up increases the setting; finger down decreases.
                        onStep(-steps)
                        stepAccumulator -= steps * stepPx
                    }
                    change.consume()
                }
            } while (event.changes.any { it.pressed })

            if (!dragging) onClick()
        }
    }
}

/** Minimal camera shutter button: a white ring with a filled centre. */
@Composable
private fun ShutterButton(capturing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).clickable(enabled = !capturing, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(72.dp).border(2.dp, Color.White, CircleShape))
        Box(
            Modifier.size(58.dp).clip(CircleShape)
                .background(if (capturing) Color.White.copy(alpha = 0.35f) else Color.White),
        )
    }
}

// --- "More" submenu ---------------------------------------------------------

/** Maps a flat [SettingKind] to its current value in the view model. */
private fun settingNameFor(kind: SettingKind): String = when (kind) {
    SettingKind.ISO -> CcapiEndpoints.SETTING_ISO
    SettingKind.TV -> CcapiEndpoints.SETTING_TV
    SettingKind.AV -> CcapiEndpoints.SETTING_AV
    SettingKind.DRIVE_MODE -> CcapiEndpoints.SETTING_DRIVE_MODE
    SettingKind.AF_OPERATION -> CcapiEndpoints.SETTING_AF_OPERATION
    SettingKind.AF_METHOD -> CcapiEndpoints.SETTING_AF_METHOD
}

private fun settingFor(vm: ControlViewModel, kind: SettingKind): CameraSetting? = when (kind) {
    SettingKind.ISO -> vm.iso
    SettingKind.TV -> vm.tv
    SettingKind.AV -> vm.av
    SettingKind.DRIVE_MODE -> vm.driveMode
    SettingKind.AF_OPERATION -> vm.afOperation
    SettingKind.AF_METHOD -> vm.afMethod
}

/** Routes a picked value to the matching view-model setter. */
private fun applyFlatSetting(vm: ControlViewModel, kind: SettingKind, value: String) = when (kind) {
    SettingKind.ISO -> vm.setIso(value)
    SettingKind.TV -> vm.setTv(value)
    SettingKind.AV -> vm.setAv(value)
    SettingKind.DRIVE_MODE -> vm.setDriveMode(value)
    SettingKind.AF_OPERATION -> vm.setAfOperation(value)
    SettingKind.AF_METHOD -> vm.setAfMethod(value)
}

/** Compact text button that opens the "More" settings sheet. */
@Composable
private fun MoreButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TuneIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("More", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * The "More" submenu: one row per additional setting, each showing its current
 * value and opening the relevant picker. Rows whose setting the camera doesn't
 * report (null) are omitted, so different bodies show only what they support.
 */
@Composable
private fun MoreSettingsSheet(
    vm: ControlViewModel,
    onPickFlat: (String) -> Unit,
    onPickImageQuality: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val flatRows = vm.moreSettings.toList().sortedWith(
        compareBy<Pair<String, CameraSetting>> { moreSettingSortIndex(it.first) }
            .thenBy { moreSettingLabel(it.first) },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        KeepImmersive()
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("More settings", style = MaterialTheme.typography.titleLarge)
            if (flatRows.isEmpty() && vm.imageQuality == null) {
                Text(
                    "This camera didn't report any of these settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(flatRows) { (name, setting) ->
                        MoreRow(
                            label = moreSettingLabel(name),
                            value = setting.value.takeIf { it.isNotBlank() }?.let { settingValueLabel(name, it) } ?: "-",
                            enabled = setting.adjustable,
                            onClick = { onPickFlat(name) },
                        )
                        HorizontalDivider()
                    }
                    vm.imageQuality?.let { iq ->
                        item {
                            MoreRow(
                                label = "Image quality",
                                value = iq.currentLabel,
                                enabled = iq.adjustable,
                                onClick = onPickImageQuality,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** A single "More" row: label on the left, current value on the right. */
@Composable
private fun MoreRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Picker for the structured image-quality setting (RAW + JPEG combinations). */
@Composable
private fun ImageQualitySheet(
    state: ImageQualityState?,
    onPick: (ImageQualityOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        KeepImmersive()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Image quality", style = MaterialTheme.typography.titleLarge)
            val options = state?.options ?: emptyList()
            val current = state?.currentLabel
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(options) { opt ->
                    val selected = opt.label == current
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(opt) }.padding(vertical = 14.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            opt.label,
                            modifier = Modifier.weight(1f),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            style = if (selected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        )
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SettingSheet(
    title: String,
    setting: CameraSetting?,
    settingName: String? = null,
    useAfMethodGrid: Boolean = false,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        KeepImmersive()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            val options = setting?.options ?: emptyList()
            val current = setting?.value
            // AF method is picked from a grid of glyphs (à la the on-camera menu)
            // rather than a text list; every other flat setting stays textual.
            if (useAfMethodGrid) {
                AfMethodGrid(options = options, current = current, onPick = onPick)
                return@Column
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(options) { opt ->
                    val selected = opt == current
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(opt) }.padding(vertical = 14.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            settingValueLabel(settingName, opt),
                            modifier = Modifier.weight(1f),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            style = if (selected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        )
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * AF-method picker rendered as a wrapping grid of glyph tiles, mirroring the
 * camera's on-body AF-method menu. Each tile draws the frame shape for its
 * method ([AfMethodTile]); the active one gets a highlighted border. The raw
 * CCAPI option string is still what we PUT back — only the presentation changes.
 */
@Composable
private fun AfMethodGrid(options: List<String>, current: String?, onPick: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        options.forEach { opt ->
            AfMethodTile(
                option = opt,
                selected = opt == current,
                onClick = { onPick(opt) },
            )
        }
    }
}

/** One AF-method choice: a rounded tile with the method's glyph, selectable. */
@Composable
private fun AfMethodTile(option: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val glyph = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = Modifier.size(64.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AfMethodIcon(afMethod = option, tint = glyph, modifier = Modifier.size(34.dp))
        }
    }
}

/**
 * The drawable resource holding the menu glyph for an AF method, classified via
 * [afFrameShapeOf]. These are the camera's own AF-method icons (white glyph on a
 * transparent background) bundled in `res/drawable-nodpi/`; we tint them at draw
 * time so they follow the tile's selection colour.
 */
@DrawableRes
private fun afMethodDrawable(afMethod: String?): Int = when (afFrameShapeOf(afMethod)) {
    AfFrameShape.FACE -> R.drawable.af_tracking
    AfFrameShape.SPOT -> R.drawable.af_spot
    AfFrameShape.POINT -> R.drawable.af_1point
    AfFrameShape.EXPAND -> R.drawable.af_expand
    AfFrameShape.EXPAND_AROUND -> R.drawable.af_expand_around
    AfFrameShape.ZONE -> R.drawable.af_zone
    AfFrameShape.LARGE_ZONE -> R.drawable.af_large_zone
    AfFrameShape.LARGE_ZONE_VERTICAL -> R.drawable.af_large_zone_vertical
}

/**
 * Shows the bundled glyph for an AF method, tinted with [tint] so it matches the
 * tile's selection state. The glyph aspect ratio is preserved (the zone glyphs
 * aren't square), so it's fit inside [modifier]'s box rather than stretched.
 */
@Composable
private fun AfMethodIcon(afMethod: String?, tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(afMethodDrawable(afMethod)),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
