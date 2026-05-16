package com.example.myapplication

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.myapplication.models.PaletteSwatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

data class BackendHealthResult(
    val ok: Boolean,
    val statusCode: Int,
    val body: String
)

data class SelfieAnalysisResult(
    val ok: Boolean,
    val statusCode: Int,
    val seasonalPalette: String?,
    val paletteDescription: String?,
    val powerSwatches: List<PaletteSwatch>,
    val neutralSwatches: List<PaletteSwatch>,
    val skinRgb: IntArray?,
    val eyeRgb: IntArray?,
    val hairRgb: IntArray?,
    /** Server-detected trait labels (for pre-filling spinners). */
    val serverSkinToneLabel: String?,
    val serverEyeColorLabel: String?,
    val serverHairColorLabel: String?,
    val rawBody: String,
    val errorMessage: String?
) {
    companion object {
        fun failure(status: Int, message: String, raw: String = "") = SelfieAnalysisResult(
            ok = false,
            statusCode = status,
            seasonalPalette = null,
            paletteDescription = null,
            powerSwatches = emptyList(),
            neutralSwatches = emptyList(),
            skinRgb = null,
            eyeRgb = null,
            hairRgb = null,
            serverSkinToneLabel = null,
            serverEyeColorLabel = null,
            serverHairColorLabel = null,
            rawBody = raw,
            errorMessage = message
        )
    }
}

@Deprecated("Use SelfieAnalysisResult from analyzeSelfieFromUri")
data class BackendAnalysisResult(
    val ok: Boolean,
    val statusCode: Int,
    val seasonalPalette: String?,
    val notes: String?,
    val rawBody: String
)

object BackendApi {
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 60000
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun deliverOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    fun testHealth(
        baseUrl: String,
        onResult: (BackendHealthResult) -> Unit
    ) {
        ioExecutor.execute {
            val endpoint = "${baseUrl.trimEnd('/')}/api/v1/health"
            val result = runCatching {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                applyCommonRequestHeaders(connection, baseUrl)
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doInput = true

                val status = connection.responseCode
                val body = readResponseBody(connection, status)
                connection.disconnect()

                BackendHealthResult(
                    ok = status in 200..299,
                    statusCode = status,
                    body = body
                )
            }.getOrElse { ex ->
                BackendHealthResult(
                    ok = false,
                    statusCode = -1,
                    body = ex.message ?: "Unknown error"
                )
            }

            deliverOnMain { onResult(result) }
        }
    }

    /**
     * Reads [imageUri] into a temp file, POSTs multipart to [baseUrl]/api/v1/analysis/selfie,
     * and parses palette RGB ranges. [onResult] runs on the main thread.
     *
     * Emulator base URL example: http://10.0.2.2:8000
     * Real device: use your PC LAN IP, e.g. http://192.168.1.15:8000
     */
    /**
     * POST JSON to [baseUrl]/api/v1/analysis/palette-from-traits.
     * [onResult] runs on the main thread.
     */
    fun postPaletteFromTraits(
        baseUrl: String,
        skinTone: String,
        eyeColor: String,
        hairColor: String,
        skinRgb: IntArray?,
        eyeRgb: IntArray?,
        hairRgb: IntArray?,
        onResult: (SelfieAnalysisResult) -> Unit
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val root = JSONObject()
                root.put("skin_tone", skinTone)
                root.put("eye_color", eyeColor)
                root.put("hair_color", hairColor)
                skinRgb?.let { root.put("skin_sample", JSONObject().put("rgb", jsonRgbArray(it))) }
                eyeRgb?.let { root.put("eye_sample", JSONObject().put("rgb", jsonRgbArray(it))) }
                hairRgb?.let { root.put("hair_sample", JSONObject().put("rgb", jsonRgbArray(it))) }

                val endpoint = "${baseUrl.trimEnd('/')}/api/v1/analysis/palette-from-traits"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                applyCommonRequestHeaders(connection, baseUrl)
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doInput = true
                connection.doOutput = true
                connection.useCaches = false
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(root.toString())
                }

                val status = connection.responseCode
                val body = readResponseBody(connection, status)
                connection.disconnect()

                if (status !in 200..299) {
                    return@runCatching SelfieAnalysisResult.failure(
                        status,
                        httpErrorDetail(body).ifBlank { "HTTP $status" },
                        body
                    )
                }
                parseAnalysisJson(body)
            }.getOrElse { ex ->
                SelfieAnalysisResult.failure(-1, ex.message ?: "Unknown error")
            }
            deliverOnMain { onResult(result) }
        }
    }

    fun analyzeSelfieFromUri(
        context: Context,
        baseUrl: String,
        imageUri: Uri,
        onResult: (SelfieAnalysisResult) -> Unit
    ) {
        ioExecutor.execute {
            val appCtx = context.applicationContext
            val file = copyUriToTempFile(appCtx, imageUri)
            if (file == null) {
                deliverOnMain {
                    onResult(SelfieAnalysisResult.failure(-1, "Could not read the image from storage."))
                }
                return@execute
            }
            try {
                val parsed = postSelfieAndParse(baseUrl, imageUri, file)
                deliverOnMain { onResult(parsed) }
            } finally {
                file.delete()
            }
        }
    }

    @Deprecated("Prefer analyzeSelfieFromUri")
    fun testSelfieUpload(
        baseUrl: String,
        imageUri: Uri,
        imageFile: File,
        onResult: (BackendAnalysisResult) -> Unit
    ) {
        ioExecutor.execute {
            val result = runCatching {
                val endpoint = "${baseUrl.trimEnd('/')}/api/v1/analysis/selfie"
                val boundary = "Boundary-${UUID.randomUUID()}"
                val mimeType = guessMimeType(imageUri.toString(), imageFile.name)

                val connection = URL(endpoint).openConnection() as HttpURLConnection
                applyCommonRequestHeaders(connection, baseUrl)
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doInput = true
                connection.doOutput = true
                connection.useCaches = false
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                DataOutputStream(connection.outputStream).use { output ->
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes(
                        "Content-Disposition: form-data; name=\"image\"; filename=\"${imageFile.name}\"\r\n"
                    )
                    output.writeBytes("Content-Type: $mimeType\r\n\r\n")
                    imageFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                    output.writeBytes("\r\n--$boundary--\r\n")
                    output.flush()
                }

                val status = connection.responseCode
                val body = readResponseBody(connection, status)
                connection.disconnect()

                val json = runCatching { JSONObject(body) }.getOrNull()
                BackendAnalysisResult(
                    ok = status in 200..299,
                    statusCode = status,
                    seasonalPalette = json?.optString("seasonal_palette"),
                    notes = json?.optString("notes"),
                    rawBody = body
                )
            }.getOrElse { ex ->
                BackendAnalysisResult(
                    ok = false,
                    statusCode = -1,
                    seasonalPalette = null,
                    notes = null,
                    rawBody = ex.message ?: "Unknown error"
                )
            }

            deliverOnMain { onResult(result) }
        }
    }

    private fun postSelfieAndParse(baseUrl: String, imageUri: Uri, imageFile: File): SelfieAnalysisResult {
        val endpoint = "${baseUrl.trimEnd('/')}/api/v1/analysis/selfie"
        val boundary = "Boundary-${UUID.randomUUID()}"
        val mimeType = guessMimeType(imageUri.toString(), imageFile.name)

        return runCatching {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            applyCommonRequestHeaders(connection, baseUrl)
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doInput = true
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            DataOutputStream(connection.outputStream).use { output ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes(
                    "Content-Disposition: form-data; name=\"image\"; filename=\"${imageFile.name}\"\r\n"
                )
                output.writeBytes("Content-Type: $mimeType\r\n\r\n")
                imageFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.writeBytes("\r\n--$boundary--\r\n")
                output.flush()
            }

            val status = connection.responseCode
            val body = readResponseBody(connection, status)
            connection.disconnect()

            if (status !in 200..299) {
                return SelfieAnalysisResult.failure(
                    status,
                    httpErrorDetail(body).ifBlank { "HTTP $status" },
                    body
                )
            }
            parseAnalysisJson(body)
        }.getOrElse { ex ->
            SelfieAnalysisResult.failure(-1, ex.message ?: "Unknown error")
        }
    }

    private fun parseAnalysisJson(body: String): SelfieAnalysisResult {
        val json = runCatching { JSONObject(body) }.getOrElse {
            return SelfieAnalysisResult.failure(-1, "Invalid JSON from server", body)
        }
        val seasonal = json.optString("seasonal_palette").takeIf { it.isNotBlank() }
        val rec = json.optJSONObject("palette_recommendation")
        val description = rec?.optString("description")?.takeIf { it.isNotBlank() }
        val power = parseSwatchList(rec?.optJSONArray("power_colors"))
        val neutral = parseSwatchList(rec?.optJSONArray("neutral_colors"))
        val traits = json.optJSONObject("traits")
        val skinRgb = traits?.optJSONObject("skin_sample")?.let { rgbFromMeasurement(it) }
        val eyeRgb = traits?.optJSONObject("eye_sample")?.let { rgbFromMeasurement(it) }
        val hairRgb = traits?.optJSONObject("hair_sample")?.let { rgbFromMeasurement(it) }
        val skinLabel = traits?.optString("skin_tone")?.takeIf { it.isNotBlank() }
        val eyeLabel = traits?.optString("eye_color")?.takeIf { it.isNotBlank() }
        val hairLabel = traits?.optString("hair_color")?.takeIf { it.isNotBlank() }

        return SelfieAnalysisResult(
            ok = true,
            statusCode = 200,
            seasonalPalette = seasonal,
            paletteDescription = description,
            powerSwatches = power,
            neutralSwatches = neutral,
            skinRgb = skinRgb,
            eyeRgb = eyeRgb,
            hairRgb = hairRgb,
            serverSkinToneLabel = skinLabel,
            serverEyeColorLabel = eyeLabel,
            serverHairColorLabel = hairLabel,
            rawBody = body,
            errorMessage = null
        )
    }

    private fun rgbFromMeasurement(o: JSONObject): IntArray? {
        val arr = o.optJSONArray("rgb") ?: return null
        if (arr.length() < 3) return null
        return intArrayOf(arr.getInt(0), arr.getInt(1), arr.getInt(2))
    }

    private fun parseSwatchList(arr: JSONArray?): List<PaletteSwatch> {
        if (arr == null) return emptyList()
        val out = ArrayList<PaletteSwatch>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val range = o.optJSONObject("rgb_range")
            val minA = when {
                range != null -> range.optJSONArray("rgb_min") ?: range.optJSONArray("rgbMin")
                else -> o.optJSONArray("rgb_min") ?: o.optJSONArray("rgbMin")
            } ?: continue
            val maxA = when {
                range != null -> range.optJSONArray("rgb_max") ?: range.optJSONArray("rgbMax")
                else -> o.optJSONArray("rgb_max") ?: o.optJSONArray("rgbMax")
            } ?: continue
            if (minA.length() < 3 || maxA.length() < 3) continue
            out.add(
                PaletteSwatch(
                    rgbMin = intArrayOf(minA.getInt(0), minA.getInt(1), minA.getInt(2)),
                    rgbMax = intArrayOf(maxA.getInt(0), maxA.getInt(1), maxA.getInt(2))
                )
            )
        }
        return out
    }

    private fun copyUriToTempFile(context: Context, uri: Uri): File? {
        return try {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val dest = File(context.cacheDir, "selfie_upload_${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest
        } catch (_: Exception) {
            null
        }
    }

    /** Free ngrok tunnels may block API clients unless this header is sent. */
    private fun applyCommonRequestHeaders(connection: HttpURLConnection, baseUrl: String) {
        if (baseUrl.contains("ngrok", ignoreCase = true)) {
            connection.setRequestProperty("ngrok-skip-browser-warning", "1")
        }
    }

    private fun readResponseBody(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun guessMimeType(uriText: String, fileName: String): String {
        val lower = (uriText + fileName).lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun jsonRgbArray(rgb: IntArray): JSONArray {
        val a = JSONArray()
        if (rgb.size >= 3) {
            a.put(rgb[0])
            a.put(rgb[1])
            a.put(rgb[2])
        }
        return a
    }

    private fun httpErrorDetail(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            when (val d = o.opt("detail")) {
                is String -> d
                is JSONArray -> buildString {
                    for (i in 0 until d.length()) {
                        val item = d.opt(i)
                        if (item is JSONObject) {
                            if (isNotEmpty()) append("; ")
                            append(item.optString("msg", item.toString()))
                        } else {
                            if (isNotEmpty()) append("; ")
                            append(item.toString())
                        }
                    }
                }
                else -> body
            }
        } catch (_: Exception) {
            body
        }
    }
}
