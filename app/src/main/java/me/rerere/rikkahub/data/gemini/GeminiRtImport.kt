package me.rerere.rikkahub.data.gemini

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

// v1038：RT导入只复用现有反重力身份/端点；输入和服务端原文绝不进入结果或异常消息。
internal enum class GeminiRtImportError {
    EMPTY_INPUT, INVALID_JSON, MISSING_REFRESH_TOKEN, UNSUPPORTED_FORMAT,
    REFRESH_FAILED, INVALID_TOKEN_RESPONSE, IDENTITY_FAILED, PROJECT_FAILED,
    STORAGE_FAILED, NETWORK_FAILED, UNKNOWN,
}

internal class GeminiRtImportException(
    val kind: GeminiRtImportError,
    val httpStatus: Int? = null,
) : Exception("RT import failed: ${kind.name}" + (httpStatus?.let { " (HTTP $it)" } ?: ""))

// Not data classes: an accidental toString must not expose a pasted credential.
internal class GeminiRtImportEntry(
    val refreshToken: String? = null,
    val error: GeminiRtImportError? = null,
)

internal class GeminiRtTokenSet(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

internal data class GeminiRtImportItem(
    val position: Int,
    val accountLabel: String? = null,
    val error: GeminiRtImportError? = null,
    val httpStatus: Int? = null,
)

internal class GeminiRtImportReport(val items: List<GeminiRtImportItem>) {
    val successCount: Int get() = items.count { it.error == null }
    val failureCount: Int get() = items.size - successCount
}

internal fun parseGeminiRefreshTokenImports(input: String, json: Json): List<GeminiRtImportEntry> {
    val text = input.trim().removePrefix("\uFEFF").trim()
    fun error(kind: GeminiRtImportError) = GeminiRtImportEntry(error = kind)
    if (text.isBlank()) return listOf(error(GeminiRtImportError.EMPTY_INPUT))
    val entries = mutableListOf<GeminiRtImportEntry>()
    fun addToken(value: String?) {
        val token = value?.trim().orEmpty()
        entries += if (token.isEmpty()) error(GeminiRtImportError.MISSING_REFRESH_TOKEN)
        else GeminiRtImportEntry(refreshToken = token)
    }
    fun visit(element: JsonElement) {
        when (element) {
            is JsonArray -> element.forEach { visit(it) }
            is JsonObject -> {
                val direct = element["refresh_token"] ?: element["refreshToken"]
                val nested = element["token"] ?: element["tokens"]
                when {
                    direct != null -> addToken((direct as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
                    element["accounts"] is JsonArray -> visit(element.getValue("accounts"))
                    nested is JsonObject || nested is JsonArray -> visit(nested!!)
                    else -> entries.add(error(GeminiRtImportError.MISSING_REFRESH_TOKEN))
                }
            }
            is JsonPrimitive -> if (element.isString) addToken(element.contentOrNull)
                else entries.add(error(GeminiRtImportError.UNSUPPORTED_FORMAT))
        }
    }
    if (text.first() in charArrayOf('{', '[', '"')) {
        val root = try { json.parseToJsonElement(text) } catch (_: Exception) {
            return listOf(error(GeminiRtImportError.INVALID_JSON))
        }
        visit(root)
    } else {
        // No token-prefix or account-count whitelist: Google, not a local guess, validates it.
        addToken(text)
    }
    if (entries.isEmpty()) return listOf(error(GeminiRtImportError.EMPTY_INPUT))
    val seen = HashSet<String>()
    return entries.filter { it.refreshToken?.let(seen::add) ?: true }
}

// v1038：只按已经由Google确认的身份合并，不能因为共用项目而覆盖另一个邮箱。
internal fun mergeGeminiRtImportedAccount(
    current: List<GeminiAccount>,
    verified: GeminiAccount,
): GeminiAccount {
    if (verified.email.isBlank()) throw GeminiRtImportException(GeminiRtImportError.IDENTITY_FAILED)
    val existing = current.firstOrNull {
        it.email.equals(verified.email, ignoreCase = true) || it.id.equals(verified.email, ignoreCase = true)
    }
    return verified.copy(
        id = existing?.id ?: verified.email,
        enabled = existing?.enabled ?: true,
        usage = existing?.usage?.takeIf { existing.projectId == verified.projectId },
    )
}

internal suspend fun runGeminiRtImport(
    input: String,
    json: Json,
    importOne: suspend (String) -> String,
    onProgress: (Int, Int) -> Unit,
): GeminiRtImportReport {
    val entries = parseGeminiRefreshTokenImports(input, json)
    val results = ArrayList<GeminiRtImportItem>(entries.size)
    onProgress(0, entries.size)
    entries.forEachIndexed { index, entry ->
        currentCoroutineContext().ensureActive()
        val position = index + 1
        val item = if (entry.error != null) {
            GeminiRtImportItem(position, error = entry.error)
        } else {
            try {
                val label = importOne(entry.refreshToken!!)
                GeminiRtImportItem(position, accountLabel = label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                when (e) {
                    is GeminiRtImportException -> GeminiRtImportItem(position, error = e.kind, httpStatus = e.httpStatus)
                    is IOException -> GeminiRtImportItem(position, error = GeminiRtImportError.NETWORK_FAILED)
                    else -> GeminiRtImportItem(position, error = GeminiRtImportError.UNKNOWN)
                }
            }
        }
        results += item
        onProgress(position, entries.size)
    }
    return GeminiRtImportReport(results)
}

// v1038：只供导入使用。取消绑定底层Call；响应体读完/关闭后才交还，停止也能打断读body。
internal suspend fun awaitGeminiRtImportResponse(call: Call): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            try {
                val detached = response.use { original ->
                    val contentType = original.body.contentType()
                    val body = original.body.string()
                    original.newBuilder().body(body.toResponseBody(contentType)).build()
                }
                continuation.resume(detached) { _, value, _ -> value.close() }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

internal class GeminiRtIdentity(val email: String, val name: String)

internal suspend fun fetchGeminiRtImportIdentity(
    accessToken: String,
    client: OkHttpClient,
    json: Json,
    userInfoUrl: String,
): GeminiRtIdentity {
    val request = Request.Builder().url(userInfoUrl).header("Authorization", "Bearer $accessToken").get().build()
    return awaitGeminiRtImportResponse(client.newCall(request)).use { response ->
        if (!response.isSuccessful) throw GeminiRtImportException(GeminiRtImportError.IDENTITY_FAILED, response.code)
        val body = try {
            json.parseToJsonElement(response.body.string()) as? JsonObject
                ?: throw GeminiRtImportException(GeminiRtImportError.IDENTITY_FAILED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            throw GeminiRtImportException(GeminiRtImportError.IDENTITY_FAILED)
        }
        fun field(key: String): String = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
        val email = field("email")
        if (email.isBlank()) throw GeminiRtImportException(GeminiRtImportError.IDENTITY_FAILED)
        GeminiRtIdentity(email, field("name").ifBlank { email })
    }
}

internal suspend fun resolveGeminiRtImportProject(discover: suspend () -> String): String = try {
    discover().also {
        currentCoroutineContext().ensureActive()
        if (it.isBlank()) throw GeminiRtImportException(GeminiRtImportError.PROJECT_FAILED)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    throw e
} catch (e: GeminiRtImportException) {
    throw e
} catch (_: Exception) {
    currentCoroutineContext().ensureActive()
    throw GeminiRtImportException(GeminiRtImportError.PROJECT_FAILED)
}

internal suspend fun exchangeGeminiRefreshToken(
    refreshToken: String,
    client: OkHttpClient,
    json: Json,
): GeminiRtTokenSet = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val call = client.newCall(
        Request.Builder()
            .url(GeminiOAuthManager.TOKEN_URL)
            .post(
                FormBody.Builder()
                    .add("client_id", GeminiOAuthManager.CLIENT_ID)
                    .add("client_secret", GeminiOAuthManager.CLIENT_SECRET)
                    .add("refresh_token", refreshToken)
                    .add("grant_type", "refresh_token")
                    .build()
            ).build()
    )
    awaitGeminiRtImportResponse(call).use { response ->
        if (!response.isSuccessful) {
            throw GeminiRtImportException(GeminiRtImportError.REFRESH_FAILED, response.code)
        }
        val token = try {
            json.parseToJsonElement(response.body.string()) as? JsonObject
                ?: throw GeminiRtImportException(GeminiRtImportError.INVALID_TOKEN_RESPONSE)
        } catch (e: GeminiRtImportException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw GeminiRtImportException(GeminiRtImportError.INVALID_TOKEN_RESPONSE)
        }
        fun string(key: String): String? = (token[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val accessToken = string("access_token")
            ?: throw GeminiRtImportException(GeminiRtImportError.INVALID_TOKEN_RESPONSE)
        val expiresIn = (token["expires_in"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?.takeIf { it > 0 && it <= (Long.MAX_VALUE - System.currentTimeMillis()) / 1000 }
            ?: 3600L
        GeminiRtTokenSet(accessToken, string("refresh_token") ?: refreshToken, expiresIn)
    }
}

// v1038：加密字节格式不变；旧文件只有在完整写入并sync后才被同目录原子替换。
internal fun writeGeminiCredentialBytesAtomically(file: File, bytes: ByteArray) {
    val directory = file.absoluteFile.parentFile ?: throw IOException("Credential directory unavailable")
    val temporary = File.createTempFile("gemini-credentials-", ".tmp", directory)
    try {
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
