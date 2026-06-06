package com.rawr.ccapi.net

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import android.net.Network
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// --- Errors --------------------------------------------------------------
// A small exception hierarchy so the UI can show clean, specific messages.

open class CcapiException(message: String) : Exception(message)
class CameraOfflineException(message: String) : CcapiException(message)
class CcapiTimeoutException(message: String) : CcapiException(message)
class AuthException(message: String) : CcapiException(message)
class UnsupportedEndpointException(message: String) : CcapiException(message)
class CcapiHttpException(val statusCode: Int, message: String) : CcapiException(message)
class DownloadCancelledException : CcapiException("Download cancelled")
class PartialDownloadException(message: String) : CcapiException(message)

// --- Models --------------------------------------------------------------

data class RawFile(
    val name: String,
    val path: String,   // CCAPI content path
    val url: String,    // absolute download URL
    val folder: String, // human-readable, e.g. "sd/100CANON"
    val size: Long? = null,
    val modified: String? = null,
    val rating: Int? = null, // in-camera star rating 0..5 (null = unknown)
)

data class FolderPage(
    val files: List<RawFile>,
    val page: Int,
    val pageCount: Int,
    val totalContents: Int,
) {
    val hasMore: Boolean get() = page < pageCount
}

/** A camera exposure setting (ISO / shutter / aperture): the current [value]
 *  and the list of selectable [options] the camera reports for it. */
data class CameraSetting(val value: String, val options: List<String>) {
    val adjustable: Boolean get() = options.size > 1
}

/** One selectable image-quality combination: a display [label] plus the raw
 *  CCAPI tokens needed to PUT it back ([raw] token + the original [jpeg] node). */
data class ImageQualityOption(val raw: String, val jpeg: JsonObject?, val label: String)

/** Flattened `stillimagequality`: the current selection's label + all options. */
data class ImageQualityState(val currentLabel: String, val options: List<ImageQualityOption>) {
    val adjustable: Boolean get() = options.size > 1
}

/**
 * CCAPI client: browse storage + download RAW files (the Import page), plus a
 * minimal shooting surface for the Control page — live view polling and a full
 * shutter press. No settings/exposure control.
 */
class CcapiClient(
    host: String,
    port: Int = 8080,
    username: String? = null,
    password: String? = null,
    scheme: String = "http",
    network: Network? = null,
) {
    val baseUrl: String = "$scheme://$host:$port"

    private val json = Json { ignoreUnknownKeys = true }
    private val http: OkHttpClient = buildHttpClient(username, password, network)

    // Endpoint paths resolved against the live root document (suffix -> full path).
    private val endpoints = HashMap<String, String>()
    private val liveViewLock = Any()
    private val afLock = Any()

    private fun buildHttpClient(username: String?, password: String?, network: Network?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        // No callTimeout: large RAW downloads can legitimately take minutes.

        // Pin every socket to the camera's Wi-Fi. This is the reliable way to
        // talk to a no-internet network on Android: the OS won't route our
        // traffic over it by default (it prefers a network with internet), so
        // we force it here regardless of the process-wide default network.
        if (network != null) {
            builder.socketFactory(network.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    network.getAllByName(hostname).toList()
            })
        }

        if (!username.isNullOrEmpty()) {
            // The camera may use Digest or Basic; dispatch on the challenge.
            val credentials = Credentials(username, password ?: "")
            val cache = ConcurrentHashMap<String, CachingAuthenticator>()
            val authenticator = DispatchingAuthenticator.Builder()
                .with("digest", DigestAuthenticator(credentials))
                .with("basic", BasicAuthenticator(credentials))
                .build()
            builder
                .authenticator(CachingAuthenticatorDecorator(authenticator, cache))
                .addInterceptor(AuthenticationCacheInterceptor(cache))
        }
        return builder.build()
    }

    // -- low level ---------------------------------------------------------

    private fun buildUrl(pathOrUrl: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val raw = when {
            pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
            pathOrUrl.startsWith("/") -> baseUrl + pathOrUrl
            else -> "$baseUrl/$pathOrUrl"
        }
        val b = raw.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private val jsonMediaType = "application/json".toMediaType()

    private fun execute(pathOrUrl: String, params: Map<String, String> = emptyMap()): Response =
        call(Request.Builder().url(buildUrl(pathOrUrl, params)).get().build())

    private fun postJson(pathOrUrl: String, body: String): Response =
        call(Request.Builder().url(buildUrl(pathOrUrl)).post(body.toRequestBody(jsonMediaType)).build())

    private fun putJson(pathOrUrl: String, body: String): Response =
        call(Request.Builder().url(buildUrl(pathOrUrl)).put(body.toRequestBody(jsonMediaType)).build())

    /** Run [request], mapping transport + HTTP errors to the CcapiException hierarchy. */
    private fun call(request: Request): Response {
        val response = try {
            http.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw CcapiTimeoutException("Camera at $baseUrl did not respond in time")
        } catch (e: ConnectException) {
            throw CameraOfflineException(offlineMessage())
        } catch (e: UnknownHostException) {
            throw CameraOfflineException(offlineMessage())
        } catch (e: IOException) {
            throw CcapiException(e.message ?: "Network error talking to $baseUrl")
        }

        when (response.code) {
            in 200..299 -> return response
            401, 403 -> {
                response.close()
                throw AuthException("Authentication failed. Check the username and password.")
            }
            404 -> {
                response.close()
                throw UnsupportedEndpointException("Endpoint not found on camera: ${request.url}")
            }
            else -> {
                val code = response.code
                response.close()
                throw CcapiHttpException(code, "CCAPI returned HTTP $code for ${request.url}")
            }
        }
    }

    private fun offlineMessage() =
        "Cannot reach camera at $baseUrl. Is it on, joined to the same Wi-Fi, and is CCAPI enabled?"

    private fun getJson(pathOrUrl: String, params: Map<String, String> = emptyMap()): JsonObject {
        execute(pathOrUrl, params).use { response ->
            val body = response.body?.string().orEmpty()
            return try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw CcapiException("Expected JSON from $pathOrUrl but got: ${body.take(200)}")
            }
        }
    }

    private fun JsonObject.pathList(): List<String> =
        (this["path"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun JsonElement.settingString(): String = when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        else -> toString()
    }

    // -- discovery ---------------------------------------------------------

    /** GET /ccapi — the capability document. Also primes endpoint discovery. */
    fun getRoot(): JsonObject {
        val root = getJson(CcapiEndpoints.ROOT)
        indexEndpoints(root)
        return root
    }

    private fun indexEndpoints(root: JsonObject) {
        // Index by version-independent suffix, keeping the highest version seen.
        val best = HashMap<String, Pair<String, String>>() // suffix -> (version, path)
        for ((version, entries) in root) {
            val array = entries as? JsonArray ?: continue
            for (entry in array) {
                val path = (entry as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull ?: continue
                val parts = path.trim('/').split("/")
                if (parts.size < 3) continue
                val suffix = parts.drop(2).joinToString("/")
                val prev = best[suffix]
                if (prev == null || version > prev.first) best[suffix] = version to path
            }
        }
        endpoints.clear()
        best.forEach { (suffix, vp) -> endpoints[suffix] = vp.second }
    }

    private fun resolve(suffix: String, fallbacks: List<String>): String {
        endpoints[suffix]?.let { return it }
        if (endpoints.isEmpty()) {
            runCatching { getRoot() }
            endpoints[suffix]?.let { return it }
        }
        return fallbacks.firstOrNull()
            ?: throw UnsupportedEndpointException("No known path for '$suffix'")
    }

    private fun settingPath(name: String): String {
        val suffix = "shooting/settings/$name"
        endpoints[suffix]?.let { return it.ensureSettingName(name) }
        if (endpoints.isEmpty()) {
            runCatching { getRoot() }
            endpoints[suffix]?.let { return it.ensureSettingName(name) }
        }
        endpoints["shooting/settings"]?.let { return it.ensureSettingName(name) }
        return CcapiEndpoints.settings(name).first()
    }

    private fun String.ensureSettingName(name: String): String {
        val path = trimEnd('/')
        return if (path.endsWith("/$name")) path else "$path/$name"
    }

    private fun discoveredSettingNames(): List<String> {
        if (endpoints.isEmpty()) runCatching { getRoot() }
        return endpoints.keys.mapNotNull { suffix ->
            suffix.removePrefix("shooting/settings/")
                .takeIf { it != suffix && it.isNotBlank() && "/" !in it }
        }
    }

    // -- public read API ---------------------------------------------------

    /** Model name, serial, firmware. */
    fun getDeviceInformation(): Map<String, String> {
        val obj = getJson(resolve("deviceinformation", CcapiEndpoints.DEVICE_INFORMATION))
        return obj.mapNotNull { (k, v) ->
            val s = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            if (s != null) k to s else null
        }.toMap()
    }

    /** Storage content roots, e.g. ["/ccapi/ver110/contents/sd"]. */
    fun listStorage(): List<String> =
        getJson(resolve("contents", CcapiEndpoints.CONTENTS)).pathList()

    /** Immediate children (sub-paths) of a contents path. */
    fun listFolder(pathOrUrl: String): List<String> = getJson(pathOrUrl).pathList()

    /** (pageCount, totalContents) for a directory via ?kind=number. */
    fun folderPageCount(folderPath: String): Pair<Int, Int> {
        return try {
            val data = getJson(folderPath, mapOf(CcapiEndpoints.PARAM_KIND to CcapiEndpoints.KIND_NUMBER))
            val pages = data["pagenumber"]?.jsonPrimitive?.intOrNull ?: 1
            val total = data["contentsnumber"]?.jsonPrimitive?.intOrNull ?: 0
            maxOf(pages, 1) to total
        } catch (e: UnsupportedEndpointException) {
            1 to 0
        }
    }

    fun getFileInfo(filePathOrUrl: String): JsonObject =
        getJson(filePathOrUrl, mapOf(CcapiEndpoints.PARAM_KIND to CcapiEndpoints.KIND_INFO))

    /** One page of RAW files in a directory (lazy: only the requested page). */
    fun listRawFiles(
        folder: String,
        page: Int = 1,
        extensions: List<String> = CcapiEndpoints.RAW_EXTENSIONS,
        withInfo: Boolean = true,
    ): FolderPage {
        val exts = extensions.map { it.lowercase() }
        val (pageCount, total) = folderPageCount(folder)
        val safePage = page.coerceIn(1, pageCount)

        val data = getJson(
            folder,
            mapOf(CcapiEndpoints.PARAM_TYPE to CcapiEndpoints.TYPE_ALL, CcapiEndpoints.PARAM_PAGE to safePage.toString()),
        )
        val label = folderLabel(folder)
        val files = data.pathList()
            .filter { p -> exts.any { p.substringAfterLast('/').lowercase().endsWith(it) } }
            .map { p ->
                RawFile(name = p.substringAfterLast('/'), path = p, url = buildUrl(p).toString(), folder = label)
            }

        val enriched = if (withInfo) files.map { f ->
            try {
                val info = getFileInfo(f.path)
                f.copy(
                    size = info["filesize"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    modified = info["lastmodifieddate"]?.jsonPrimitive?.contentOrNull,
                    rating = parseRating(info),
                )
            } catch (e: CcapiException) {
                f // metadata is best-effort; never fail the whole listing
            }
        } else files

        return FolderPage(enriched, safePage, pageCount, total)
    }

    /**
     * Fetch a preview JPEG for a file: (bytes, contentType).
     * [kind] is one of [CcapiEndpoints.KIND_THUMBNAIL] (tiny embedded preview)
     * or [CcapiEndpoints.KIND_DISPLAY] (larger, sharper preview).
     */
    fun getImage(filePathOrUrl: String, kind: String = CcapiEndpoints.KIND_THUMBNAIL): Pair<ByteArray, String> {
        execute(filePathOrUrl, mapOf(CcapiEndpoints.PARAM_KIND to kind)).use { response ->
            val type = response.header("Content-Type") ?: "image/jpeg"
            return (response.body?.bytes() ?: ByteArray(0)) to type
        }
    }

    // -- shooting / live view (the "Control" page) -------------------------

    /**
     * Start live view at [size] (small/medium), leaving the camera's own display
     * untouched. Throws if the camera refuses (e.g. wrong mode).
     */
    fun startLiveView(size: String = CcapiEndpoints.LIVEVIEW_SIZE_MEDIUM) {
        synchronized(liveViewLock) {
            val settleDelaysMs = longArrayOf(300, 600, 1000, 1500, 2200)
            repeat(settleDelaysMs.size + 1) { attempt ->
                try {
                    setLiveView(size)
                    return
                } catch (e: CcapiHttpException) {
                    if (e.statusCode != 503) throw e
                    if (attempt == settleDelaysMs.size) return@repeat
                    // 503 here usually means the camera still thinks live view
                    // is busy from a previous session. Turn it off, let it
                    // settle, then try the requested size again.
                    runCatching { setLiveView(CcapiEndpoints.LIVEVIEW_SIZE_OFF) }
                    Thread.sleep(settleDelaysMs[attempt])
                }
            }
            throw CcapiHttpException(
                503,
                "Camera is still busy starting live view after retrying. Wait a moment, make sure it is ready to shoot, then retry.",
            )
        }
    }

    private fun setLiveView(size: String) {
        postJson(
            resolve("shooting/liveview", CcapiEndpoints.LIVEVIEW),
            """{"liveviewsize":"$size","cameradisplay":"${CcapiEndpoints.LIVEVIEW_DISPLAY_KEEP}"}""",
        ).close()
    }

    /** Stop live view. Best-effort: never throws (called on teardown). */
    fun stopLiveView() {
        synchronized(liveViewLock) {
            runCatching {
                setLiveView(CcapiEndpoints.LIVEVIEW_SIZE_OFF)
            }
        }
    }

    /**
     * Latest live-view JPEG frame, or null when none is ready yet — the camera
     * returns a non-2xx between frames / during capture, which we treat as "skip
     * this frame". Throws only on a hard disconnect so the poll loop can stop.
     */
    fun getLiveViewFrame(): ByteArray? {
        val request = Request.Builder()
            .url(buildUrl(resolve("shooting/liveview/flip", CcapiEndpoints.LIVEVIEW_FLIP)))
            .get().build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            return null // transient; try again on the next poll
        } catch (e: ConnectException) {
            throw CameraOfflineException(offlineMessage())
        } catch (e: UnknownHostException) {
            throw CameraOfflineException(offlineMessage())
        } catch (e: IOException) {
            throw CcapiException(e.message ?: "Network error talking to $baseUrl")
        }
        response.use {
            return if (it.code in 200..299) it.body?.bytes()?.takeIf { b -> b.isNotEmpty() } else null
        }
    }

    /** Full shutter press: autofocus (when [af]) then capture. */
    fun pressShutter(af: Boolean = true) {
        postJson(
            resolve("shooting/control/shutterbutton", CcapiEndpoints.SHUTTER_BUTTON),
            """{"af":$af}""",
        ).close()
    }

    // -- exposure settings (ISO / shutter / aperture) ----------------------

    /** Current value + selectable options for a setting (e.g. "iso", "tv", "av"). */
    fun getSetting(name: String): CameraSetting {
        val obj = getJson(settingPath(name))
        val value = obj["value"]?.settingString() ?: ""
        val options = (obj["ability"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        return CameraSetting(value, options)
    }

    /** Set a setting to one of its option values. Throws if the camera refuses. */
    fun putSetting(name: String, value: String) {
        // buildJsonObject escapes values safely — some Tv options contain a quote
        // (e.g. 30" = 30 seconds).
        val body = buildJsonObject { put("value", value) }.toString()
        putJson(settingPath(name), body).close()
    }

    fun getMoreSettings(candidateNames: List<String> = CcapiEndpoints.MORE_SETTING_CANDIDATES): Map<String, CameraSetting> {
        val exposureSettings = setOf(CcapiEndpoints.SETTING_ISO, CcapiEndpoints.SETTING_TV, CcapiEndpoints.SETTING_AV)
        val structuredSettings = setOf(CcapiEndpoints.SETTING_STILL_IMAGE_QUALITY)
        val names = (candidateNames + discoveredSettingNames())
            .distinct()
            .filterNot { it in exposureSettings || it in structuredSettings }
        return buildMap {
            names.forEach { name ->
                val setting = runCatching { getSetting(name) }.getOrNull()
                if (setting != null && (setting.value.isNotBlank() || setting.options.isNotEmpty())) {
                    put(name, setting)
                }
            }
        }
    }

    // -- image quality (structured RAW + JPEG, unlike the flat settings) ----

    /**
     * Read `stillimagequality` and flatten it into selectable combinations.
     *
     * CCAPI reports this setting structured, not as a flat list. Its `value` is
     * always `{"raw":<token>,"jpeg":<obj?>}`. The `ability` shape, however,
     * varies by body/firmware, so we handle both forms seen in the wild:
     *   - list form:  "ability":[{"raw":..,"jpeg":..}, ...]  (valid combos)
     *   - axis form:  "ability":{"raw":[..],"jpeg":[{..},..]} (independent axes)
     * The list form is used verbatim (no fabricated combos); the axis form is
     * expanded into the cross-product. Best-effort: returns null if the camera
     * doesn't report it or neither shape matches, so the UI just hides the row.
     */
    fun getImageQuality(): ImageQualityState? {
        val obj = try {
            getJson(settingPath("stillimagequality"))
        } catch (e: CcapiException) {
            return null
        }
        val value = obj["value"]?.jsonObject ?: return null
        val ability = obj["ability"]

        val options = when (ability) {
            // List form: each entry is already a valid {raw, jpeg} combination.
            is JsonArray -> ability.mapNotNull { it as? JsonObject }.map { combo ->
                val raw = combo["raw"]?.jsonPrimitive?.contentOrNull ?: "none"
                val jpeg = combo["jpeg"] as? JsonObject
                ImageQualityOption(raw = raw, jpeg = jpeg, label = imageQualityLabel(raw, jpeg))
            }
            // Axis form: cross-product the independent raw + jpeg option lists.
            is JsonObject -> {
                val raws = (ability["raw"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val jpegs = (ability["jpeg"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                if (raws.isEmpty() && jpegs.isEmpty()) return null
                buildList<ImageQualityOption> {
                    val rawTokens = raws.ifEmpty { listOf("none") }
                    val jpegChoices: List<JsonObject?> = if (jpegs.isEmpty()) listOf(null) else buildList {
                        if (jpegs.none { isJpegOff(it) }) add(null) // allow "RAW only"
                        addAll(jpegs)
                    }
                    for (raw in rawTokens) for (jpeg in jpegChoices) {
                        if (isRawOff(raw) && jpeg == null) continue // both off = invalid
                        add(ImageQualityOption(raw = raw, jpeg = jpeg, label = imageQualityLabel(raw, jpeg)))
                    }
                }
            }
            else -> return null
        }
        if (options.isEmpty()) return null

        val currentRaw = value["raw"]?.jsonPrimitive?.contentOrNull ?: "none"
        val currentJpeg = value["jpeg"] as? JsonObject
        val currentLabel = imageQualityLabel(currentRaw, currentJpeg)
        return ImageQualityState(currentLabel = currentLabel, options = options.distinctBy { it.label })
    }

    /** Apply an image-quality option chosen from [getImageQuality]'s list. */
    fun putImageQuality(option: ImageQualityOption) {
        val body = buildJsonObject {
            put("value", buildJsonObject {
                put("raw", option.raw)
                option.jpeg?.let { put("jpeg", it) }
            })
        }.toString()
        putJson(
            settingPath("stillimagequality"),
            body,
        ).close()
    }

    private fun isRawOff(raw: String) = raw.equals("none", true) || raw.isBlank()

    private fun isJpegOff(jpeg: JsonObject): Boolean {
        val size = jpeg["size"]?.jsonPrimitive?.contentOrNull
        return size == null || size.equals("none", true)
    }

    /** Human label for a (raw, jpeg) combination, e.g. "RAW + JPEG L/Fine". */
    private fun imageQualityLabel(raw: String, jpeg: JsonObject?): String {
        val rawPart = when {
            isRawOff(raw) -> null
            raw.equals("craw", true) -> "CRAW"
            else -> "RAW"
        }
        val jpegPart = jpeg?.takeUnless { isJpegOff(it) }?.let {
            val size = it["size"]?.jsonPrimitive?.contentOrNull?.let(::jpegSizeLabel) ?: ""
            val quality = it["quality"]?.jsonPrimitive?.contentOrNull?.replaceFirstChar { c -> c.uppercase() } ?: ""
            "JPEG ${listOf(size, quality).filter(String::isNotBlank).joinToString(" ")}".trim()
        }
        return listOfNotNull(rawPart, jpegPart).joinToString(" + ").ifBlank { "—" }
    }

    private fun jpegSizeLabel(size: String): String = when (size.lowercase()) {
        "large" -> "L"
        "middle", "medium" -> "M"
        "small", "small1" -> "S1"
        "small2" -> "S2"
        else -> size.replaceFirstChar { it.uppercase() }
    }

    // -- touch focus -------------------------------------------------------

    /** Move the AF frame to live-view coordinates [x],[y]. */
    fun setAfFramePosition(x: Int, y: Int) {
        val body = buildJsonObject {
            // Canon accepts the PUT even when it ignores unknown coordinate
            // keys, which moves the frame to (0,0). Send x/y first; keep the
            // older names as harmless compatibility aliases for other bodies.
            put("x", x)
            put("y", y)
            put("positionx", x)
            put("positiony", y)
            put("positionX", x)
            put("positionY", y)
            put("position", buildJsonObject {
                put("x", x)
                put("y", y)
            })
        }.toString()
        putJson(
            resolve("shooting/liveview/afframeposition", CcapiEndpoints.LIVEVIEW_AFFRAME),
            body,
        ).close()
    }

    /** Start/stop driving autofocus (at the current AF frame). */
    fun driveAf(start: Boolean) {
        synchronized(afLock) {
            val settleDelaysMs = if (start) longArrayOf(120, 250, 400) else longArrayOf(80, 160)
            repeat(settleDelaysMs.size + 1) { attempt ->
                try {
                    postJson(
                        resolve("shooting/control/af", CcapiEndpoints.CONTROL_AF),
                        """{"action":"${if (start) "start" else "stop"}"}""",
                    ).close()
                    return
                } catch (e: CcapiHttpException) {
                    if (e.statusCode != 503) throw e
                    if (!start && attempt == settleDelaysMs.size) return
                    if (attempt == settleDelaysMs.size) return@repeat
                    Thread.sleep(settleDelaysMs[attempt])
                }
            }
            throw CcapiHttpException(503, "Camera is busy focusing. Wait a moment, then try again.")
        }
    }

    // -- download ----------------------------------------------------------

    /**
     * Stream a file to [sink] in chunks without buffering the whole RAW in
     * memory. Reports bytes via [onProgress]; aborts when [isCancelled] returns
     * true. Throws [DownloadCancelledException] / [PartialDownloadException] so
     * the caller can clean up. Does NOT close [sink].
     */
    fun download(
        fileUrl: String,
        sink: OutputStream,
        isCancelled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        chunkSize: Int = 1024 * 1024,
    ) {
        execute(fileUrl).use { response ->
            val body = response.body ?: throw CcapiException("Empty response body for $fileUrl")
            val total = body.contentLength().takeIf { it >= 0 }
            val source = body.byteStream()
            val buffer = ByteArray(chunkSize)
            var done = 0L
            while (true) {
                if (isCancelled()) throw DownloadCancelledException()
                val read = source.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                done += read
                onProgress(done, total)
            }
            sink.flush()
            if (total != null && done != total) {
                throw PartialDownloadException("Partial download for $fileUrl: got $done of $total bytes")
            }
        }
    }

    /**
     * Normalise CCAPI's `rating` field to 0..5. Canon returns it either as an
     * integer or as a string ("off"/"none" for unrated, otherwise "1".."5").
     * Returns null when the field is absent so callers can tell "unrated" (0)
     * apart from "camera didn't report a rating".
     */
    private fun parseRating(info: JsonObject): Int? {
        val prim = info["rating"]?.jsonPrimitive ?: return null
        prim.intOrNull?.let { return it.coerceIn(0, 5) }
        return when (val s = prim.contentOrNull?.trim()?.lowercase()) {
            null, "", "off", "none" -> 0
            else -> s.toIntOrNull()?.coerceIn(0, 5)
        }
    }

    private fun folderLabel(folderPath: String): String {
        val parts = folderPath.trim('/').split("/")
        // drop the /ccapi/verXXX/contents prefix (first 3 segments)
        return if (parts.size > 3) parts.drop(3).joinToString("/") else folderPath
    }
}
