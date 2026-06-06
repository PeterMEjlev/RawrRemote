package com.rawr.ccapi.net

/**
 * Canon CCAPI endpoint constants.
 *
 * Everything that depends on the exact CCAPI URL layout lives here so it is
 * easy to adjust if Canon changes path names. The official reference (Canon
 * Camera Control API) is the source of truth.
 *
 * The CCAPI root (`GET /ccapi`) advertises every endpoint the connected camera
 * actually supports, grouped by API version. [CcapiClient] discovers the real
 * path from that document and only falls back to the constants below when
 * discovery is not possible. Keep fallbacks "newest version first".
 */
object CcapiEndpoints {
    const val ROOT = "/ccapi"

    val DEVICE_INFORMATION = listOf(
        "/ccapi/ver100/deviceinformation",
    )

    val DEVICE_STATUS_STORAGE = listOf(
        "/ccapi/ver110/devicestatus/storage",
        "/ccapi/ver100/devicestatus/storage",
    )

    // File-system "contents" tree: the entry point for browsing the card.
    //   GET <contents>                        -> list of storages
    //   GET <contents>/<storage>              -> list of directories
    //   GET <contents>/<storage>/<dir>        -> list of files (paginated)
    //   GET <contents>/<storage>/<dir>/<file> -> download the file
    val CONTENTS = listOf(
        "/ccapi/ver130/contents",
        "/ccapi/ver120/contents",
        "/ccapi/ver110/contents",
        "/ccapi/ver100/contents",
    )

    // Query parameters for the contents listing.
    const val PARAM_KIND = "kind"
    const val PARAM_TYPE = "type"
    const val PARAM_PAGE = "page"

    const val KIND_NUMBER = "number"      // ask for counts + page count
    const val KIND_INFO = "info"          // ask for a single file's metadata
    const val KIND_THUMBNAIL = "thumbnail" // embedded preview JPEG
    const val KIND_DISPLAY = "display"    // larger preview
    const val KIND_MAIN = "main"          // full-resolution original (default download)

    const val TYPE_ALL = "all"

    val RAW_EXTENSIONS = listOf(".cr3")

    // -- shooting / control (the "Control" page) --------------------------
    // Live view: POST to start/stop, then GET the "flip" image to poll frames.
    //   POST <liveview>            {"liveviewsize":"medium","cameradisplay":"keep"}
    //   GET  <liveview>/flip       -> a single live-view JPEG frame
    //   POST <shutterbutton>       {"af":true}  -> autofocus + capture
    val LIVEVIEW = listOf("/ccapi/ver100/shooting/liveview")
    val LIVEVIEW_FLIP = listOf("/ccapi/ver100/shooting/liveview/flip")
    val SHUTTER_BUTTON = listOf("/ccapi/ver100/shooting/control/shutterbutton")

    const val LIVEVIEW_SIZE_MEDIUM = "medium"
    const val LIVEVIEW_SIZE_OFF = "off"
    const val LIVEVIEW_DISPLAY_KEEP = "keep" // leave the camera's own display as-is

    // Exposure settings: GET -> {"value":..,"ability":[..]}, PUT {"value":..}.
    fun settings(name: String) = listOf("/ccapi/ver100/shooting/settings/$name")
    const val SETTING_ISO = "iso"
    const val SETTING_TV = "tv" // shutter speed
    const val SETTING_AV = "av" // aperture

    // Additional shooting settings (the Control page's "More" submenu). These
    // use the same flat {"value":..,"ability":[..]} shape as the exposure
    // settings, so they go through getSetting/putSetting unchanged.
    const val SETTING_DRIVE_MODE = "drive"
    const val SETTING_AF_OPERATION = "afoperation"
    const val SETTING_AF_METHOD = "afmethod"
    // Image quality is structured ({"value":{"raw":..,"jpeg":..},"ability":..});
    // handled by getImageQuality/putImageQuality, not the flat helpers.
    const val SETTING_STILL_IMAGE_QUALITY = "stillimagequality"

    val MORE_SETTING_CANDIDATES = listOf(
        SETTING_DRIVE_MODE,
        SETTING_AF_OPERATION,
        SETTING_AF_METHOD,
        "shootingmodedial",
        "exposure",
        "wb",
        "colortemperature",
        "metering",
        "stillimageaspectratio",
        "flash",
        "aeb",
        "wbshift",
        "wbbracket",
        "colorspace",
        "picturestyle",
        "shuttermode",
        "trackingsetting",
        "hdr",
        "antiflickershoot",
        "focusbracketing",
        "highframerate",
        "moviequality",
        "soundrecording",
    )

    // AF-method tokens CCAPI reports for `afmethod`. Used to shape the live-view
    // focus reticle. Canon's naming varies a little across bodies/firmware, so
    // the reticle code matches loosely (lowercased, ignoring spaces/hyphens)
    // rather than requiring an exact string from this list.
    const val AF_METHOD_SPOT = "spotAF"
    const val AF_METHOD_1POINT = "1-pointAF"
    const val AF_METHOD_EXPAND = "expandAFarea"           // 1-point + up/down/left/right
    const val AF_METHOD_EXPAND_AROUND = "expandAFareaaround" // 1-point + surrounding
    const val AF_METHOD_ZONE = "zoneAF"
    const val AF_METHOD_FLEXIBLE_ZONE_1 = "flexiblezoneAF1"
    const val AF_METHOD_FLEXIBLE_ZONE_2 = "flexiblezoneAF2"
    const val AF_METHOD_FLEXIBLE_ZONE_3 = "flexiblezoneAF3"
    const val AF_METHOD_WHOLE_AREA = "wholeareaAF"

    // Touch focus: set the AF frame, then drive AF. Canon expects coordinates
    // in the full AF/sensor grid here, not the downsampled live-view JPEG.
    // EOS R5/R5 II large stills are 8192x5464.
    const val LIVEVIEW_AFFRAME_COORD_WIDTH = 8192
    const val LIVEVIEW_AFFRAME_COORD_HEIGHT = 5464
    val LIVEVIEW_AFFRAME = listOf("/ccapi/ver100/shooting/liveview/afframeposition")
    val CONTROL_AF = listOf("/ccapi/ver100/shooting/control/af")
}
