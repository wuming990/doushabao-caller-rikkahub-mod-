package me.rerere.rikkahub.data.gemini

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Behavioral tests use reflection only so shipped v1037 compiles and fails assertions, not symbols. */
class GeminiRtImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }

    private fun implementation(): Class<*> = try {
        Class.forName("me.rerere.rikkahub.data.gemini.GeminiRtImportKt")
    } catch (error: ClassNotFoundException) {
        throw AssertionError("GeminiRtImport implementation is missing", error)
    }

    private fun invoke(name: String, vararg arguments: Any?): Any? {
        val method = implementation().methods.firstOrNull { it.name == name && it.parameterCount == arguments.size }
            ?: throw AssertionError("GeminiRtImport missing function $name")
        return try { method.invoke(null, *arguments) } catch (e: InvocationTargetException) { throw e.targetException }
    }

    private suspend fun invokeSuspend(name: String, vararg arguments: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            invoke(name, *arguments, continuation)
        }

    private fun property(value: Any, name: String): Any? =
        value.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() }).invoke(value)

    private fun parse(value: String): List<*> = invoke("parseGeminiRefreshTokenImports", value, json) as List<*>
    private fun tokens(value: String): List<String> = parse(value).mapNotNull { property(it!!, "refreshToken") as String? }
    private fun firstError(value: String): String = property(parse(value).single()!!, "error").toString()
    private fun items(report: Any): List<*> = property(report, "items") as List<*>
    private fun count(report: Any, name: String): Int = property(report, name) as Int
    private suspend fun runImport(
        input: String,
        progress: (Int, Int) -> Unit = { _, _ -> },
        importOne: suspend (String) -> String,
    ): Any = invokeSuspend("runGeminiRtImport", input, json, importOne, progress)!!

    @Test fun `plain RT is trimmed without a prefix whitelist`() {
        assertEquals(listOf("fake-arbitrary-refresh"), tokens("  fake-arbitrary-refresh  \n"))
    }
    @Test fun `UTF8 BOM does not become part of the RT`() {
        assertEquals(listOf("fake-one"), tokens("\uFEFF  fake-one  "))
    }
    @Test fun `snake case object extracts only refresh token`() {
        assertEquals(listOf("fake-rt"), tokens("""{"refresh_token":"fake-rt","access_token":"fake-old-at","email":"untrusted@example.invalid","project_id":"untrusted"}"""))
    }
    @Test fun `camel case object is accepted`() {
        assertEquals(listOf("fake-two"), tokens("""{"refreshToken":"fake-two"}"""))
    }
    @Test fun `JSON string token is accepted`() {
        assertEquals(listOf("fake-three"), tokens("\"fake-three\""))
    }
    @Test fun `exported nested token object is accepted`() {
        assertEquals(listOf("fake-four"), tokens("""{"email":"untrusted@example.invalid","token":{"refresh_token":"fake-four"}}"""))
    }
    @Test fun `accounts wrapper preserves account order`() {
        assertEquals(listOf("fake-a", "fake-b"), tokens("""{"accounts":[{"refresh_token":"fake-a"},{"refresh_token":"fake-b"}]}"""))
    }
    @Test fun `string arrays are imported in order`() {
        assertEquals(listOf("fake-a", "fake-b"), tokens("""["fake-a","fake-b"]"""))
    }
    @Test fun `only exactly repeated tokens are deduplicated`() {
        assertEquals(listOf("fake-a", "fake-b", "FAKE-a"), tokens("""["fake-a","fake-b","fake-a","FAKE-a"]"""))
    }
    @Test fun `empty input produces an actionable error`() {
        assertEquals("EMPTY_INPUT", firstError("  \n "))
    }
    @Test fun `malformed JSON is not sent as a raw RT`() {
        assertEquals("INVALID_JSON", firstError("""{"refresh_token":"fake-private-rt""""))
    }
    @Test fun `object without RT does not use its access token instead`() {
        assertEquals("MISSING_REFRESH_TOKEN", firstError("""{"access_token":"fake-at-only"}"""))
    }
    @Test fun `empty RT is not a successful input`() {
        assertEquals("MISSING_REFRESH_TOKEN", firstError("""{"refresh_token":"  "}"""))
    }
    @Test fun `number in JSON array is not converted into a credential`() {
        assertEquals("UNSUPPORTED_FORMAT", firstError("[123]"))
    }
    @Test fun `empty JSON array yields an empty input error`() {
        assertEquals("EMPTY_INPUT", firstError("[]"))
    }
    @Test fun `bad records do not prevent later good records from importing`() = runBlocking {
        val actual = mutableListOf<String>()
        val report = runImport("""["fake-a",{},"fake-b"]""") { token -> actual += token; "verified-$token@example.invalid" }
        assertEquals(listOf("fake-a", "fake-b"), actual)
        assertEquals(2, count(report, "successCount")); assertEquals(1, count(report, "failureCount"))
        assertEquals("MISSING_REFRESH_TOKEN", property(items(report)[1]!!, "error").toString())
    }
    @Test fun `progress finishes with every parsed record accounted for`() = runBlocking {
        val progress = mutableListOf<Pair<Int, Int>>()
        runImport("""["fake-a",{},"fake-b"]""", { done, total -> progress += done to total }) { "verified@example.invalid" }
        assertEquals(3 to 3, progress.last())
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress.filter { it.first > 0 })
    }
    @Test fun `network error preserves subsequent import opportunity`() = runBlocking {
        val report = runImport("""["fake-a","fake-b"]""") { if (it == "fake-a") throw IOException("fake-private-rt"); "verified@example.invalid" }
        assertEquals(1, count(report, "successCount")); assertEquals(1, count(report, "failureCount"))
        assertEquals("NETWORK_FAILED", property(items(report)[0]!!, "error").toString())
        assertFalse(report.toString().contains("fake-private-rt"))
    }
    @Test fun `unknown exception cannot leak its credential bearing message`() = runBlocking {
        val report = runImport("fake-input-rt") { throw IllegalArgumentException("fake-private-rt payload") }
        val item = items(report).single()!!
        assertEquals("UNKNOWN", property(item, "error").toString())
        assertFalse(item.toString().contains("fake-private-rt")); assertFalse(item.toString().contains("payload"))
    }
    @Test fun `cancellation stops before importing another account`() = runBlocking {
        val visited = mutableListOf<String>(); val cancelled = CancellationException("user cancelled")
        val failure = runCatching {
            runImport("""["fake-a","fake-b"]""") { visited += it; throw cancelled }
        }.exceptionOrNull()
        if (failure is AssertionError) throw failure
        assertSame(cancelled, failure); assertEquals(listOf("fake-a"), visited)
    }
    @Test fun `reported label comes from validated result not input email`() = runBlocking {
        val report = runImport("""{"refresh_token":"fake-rt","email":"untrusted@example.invalid"}""") { "verified@example.invalid" }
        assertEquals("verified@example.invalid", property(items(report).single()!!, "accountLabel"))
    }
    @Test fun `batch retains safe HTTP status from import exception`() = runBlocking {
        implementation()
        val enumClass = Class.forName("me.rerere.rikkahub.data.gemini.GeminiRtImportError")
        val kind = enumClass.enumConstants.first { it.toString() == "REFRESH_FAILED" }
        val exceptionClass = Class.forName("me.rerere.rikkahub.data.gemini.GeminiRtImportException")
        val exception = exceptionClass.getConstructor(enumClass, Integer::class.java).newInstance(kind, 429) as Throwable
        val report = runImport("fake-rt") { throw exception }
        assertEquals(429, property(items(report).single()!!, "httpStatus"))
    }

    private fun account(id: String, email: String = id, project: String = "project-a", enabled: Boolean = true,
        usage: GeminiUsageSnapshot? = null) = GeminiAccount(id, email, email, project, "fake-at", "fake-rt", 1000000,
            enabled = enabled, tokenStatus = GeminiTokenStatus.AVAILABLE, usage = usage)
    private fun merge(old: List<GeminiAccount>, fresh: GeminiAccount): GeminiAccount =
        invoke("mergeGeminiRtImportedAccount", old, fresh) as GeminiAccount

    @Test fun `same verified email preserves disabled choice and stable account id`() {
        val old = account("old-id", "verified@example.invalid", enabled = false)
        val result = merge(listOf(old), account("new-id", "verified@example.invalid"))
        assertEquals("old-id", result.id); assertFalse(result.enabled)
    }
    @Test fun `verified email matching is case insensitive`() {
        val result = merge(listOf(account("old-id", "Verified@Example.Invalid")), account("new-id", "verified@example.invalid"))
        assertEquals("old-id", result.id)
    }
    @Test fun `same project cannot overwrite another verified email`() {
        val result = merge(listOf(account("other-id", "other@example.invalid")), account("new-id", "verified@example.invalid"))
        assertEquals("verified@example.invalid", result.id); assertTrue(result.enabled)
    }
    @Test fun `same account and project keep last quota snapshot`() {
        val usage = GeminiUsageSnapshot(updatedAt = 42)
        val result = merge(listOf(account("old-id", "verified@example.invalid", usage = usage)), account("new-id", "verified@example.invalid"))
        assertSame(usage, result.usage)
    }
    @Test fun `changed verified project does not reuse old quota`() {
        val result = merge(listOf(account("old-id", "verified@example.invalid", usage = GeminiUsageSnapshot())),
            account("new-id", "verified@example.invalid", project = "project-b"))
        assertNull(result.usage); assertEquals("project-b", result.projectId)
    }
    @Test fun `new credentials replace old token without mutating supplied old account`() {
        val old = account("old-id", "verified@example.invalid")
        val fresh = account("new-id", "verified@example.invalid").copy(accessToken = "fake-new-at", refreshToken = "fake-new-rt")
        val result = merge(listOf(old), fresh)
        assertEquals("fake-new-at", result.accessToken); assertEquals("fake-new-rt", result.refreshToken)
        assertEquals("fake-at", old.accessToken); assertEquals("fake-rt", old.refreshToken)
    }

    @Test fun `response body network failure is not mislabeled as invalid JSON`() = runBlocking {
        val failing = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("fake-private-body-read")
            }.buffer()
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(failing).build()
        }.build()
        val report = runImport("fake-rt") { exchange(http); "verified@example.invalid" }
        assertEquals("NETWORK_FAILED", property(items(report).single()!!, "error").toString())
    }
    @Test fun `stopping import cancels its pending underlying HTTP call`() = runBlocking {
        assertTrue("GeminiRtImport missing function awaitGeminiRtImportResponse",
            implementation().methods.any { it.name == "awaitGeminiRtImportResponse" })
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val delegate = OkHttpClient().newCall(Request.Builder().url("https://example.invalid/never-connect").build())
        val call = object : Call by delegate {
            override fun request(): Request = Request.Builder().url("https://example.invalid/never-connect").build()
            override fun execute(): Response = throw UnsupportedOperationException()
            override fun enqueue(responseCallback: Callback) { started.complete(Unit) }
            override fun cancel() { cancelled = true }
            override fun isExecuted(): Boolean = started.isCompleted
            override fun isCanceled(): Boolean = cancelled
            override fun timeout(): Timeout = Timeout.NONE
            override fun clone(): Call = this
        }
        val operation = launch { invokeSuspend("awaitGeminiRtImportResponse", call) }
        started.await(); operation.cancelAndJoin()
        assertTrue("Underlying import request must not outlive Stop", cancelled)
    }
    @Test fun `stop during body read closes response and never starts next account`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.complete(Unit)
                if (!unblock.await(5, TimeUnit.SECONDS)) throw IOException("fake-body-fixture-timeout")
                throw IOException("fake-body-cancelled")
            }
            override fun close() { closed.set(true); super.close() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        val template = OkHttpClient().newCall(Request.Builder().url("https://example.invalid/never-connect").build())
        val call = object : Call by template {
            override fun enqueue(responseCallback: Callback) {
                val owner: Call = this
                thread(name = "rt-import-body-test", isDaemon = true) {
                    try {
                        responseCallback.onResponse(owner, Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("fixture").body(body).build())
                    } finally { callbackDone.countDown() }
                }
            }
            override fun cancel() { cancelled.set(true); unblock.countDown() }
            override fun isCanceled(): Boolean = cancelled.get()
        }
        val visited = mutableListOf<String>()
        val stop = CancellationException("test user stop")
        var propagated: CancellationException? = null
        val operation = launch {
            try {
                runImport("""["fake-a","fake-b"]""") { token ->
                    visited += token
                    (invokeSuspend("awaitGeminiRtImportResponse", call) as Response).use { it.body.string() }
                    "verified@example.invalid"
                }
            } catch (e: CancellationException) { propagated = e; throw e }
        }
        try {
            withTimeout(5_000) { reading.await() }
            operation.cancel(stop)
            operation.join()
            assertTrue("Current HTTP call must be cancelled", cancelled.get())
            // Coroutine stack-trace recovery may copy an exception while retaining the original as its cause.
            // Require causal identity with this Stop, not merely a matching type/message or JVM object identity.
            val seenCauses = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var currentCause: Throwable? = propagated
            var originalStopReached = false
            while (currentCause != null && seenCauses.add(currentCause)) {
                if (currentCause === stop) { originalStopReached = true; break }
                currentCause = currentCause.cause
            }
            assertTrue("The same user Stop must be preserved directly or in the recovery cause chain", originalStopReached)
            assertEquals(listOf("fake-a"), visited)
            assertTrue("Response callback must finish", withContext(Dispatchers.IO) { callbackDone.await(5, TimeUnit.SECONDS) })
            assertTrue("Original response source must be closed", closed.get())
        } finally {
            unblock.countDown()
            operation.cancelAndJoin()
        }
    }

    @Test fun `identity transport error remains a network failure`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { throw IOException("fake-private-identity") }.build()
        val report = runImport("fake-rt") {
            invokeSuspend("fetchGeminiRtImportIdentity", "fake-at", http, json,
                "https://www.googleapis.com/oauth2/v1/userinfo?alt=json")
            "verified@example.invalid"
        }
        assertEquals("NETWORK_FAILED", property(items(report).single()!!, "error").toString())
    }
    @Test fun `identity uses Google verified data and the supplied existing endpoint`() = runBlocking {
        val http = client(body = """{"email":"verified@example.invalid","name":"Verified"}""") { request ->
            assertEquals("https://www.googleapis.com/oauth2/v1/userinfo?alt=json", request.url.toString())
            assertEquals("Bearer fake-at", request.header("Authorization")); assertEquals("GET", request.method)
        }
        val identity = invokeSuspend("fetchGeminiRtImportIdentity", "fake-at", http, json,
            "https://www.googleapis.com/oauth2/v1/userinfo?alt=json")!!
        assertEquals("verified@example.invalid", property(identity, "email")); assertEquals("Verified", property(identity, "name"))
    }
    @Test fun `project transport error remains a network failure`() = runBlocking {
        var parserReached = false
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("fake-private-project")
            }.buffer()
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
        }.build()
        val discover: suspend () -> String = {
            val request = Request.Builder().url("https://cloudcode-pa.googleapis.com/v1internal:listCloudAICompanionProjects")
                .header("Authorization", "Bearer fake-at").post("{}".toRequestBody()).build()
            val response = invokeSuspend("awaitGeminiRtImportResponse", http.newCall(request)) as Response
            response.use {
                parserReached = true
                // Mirror the downstream parse fallback. Remote I/O must fail BEFORE reaching it.
                try { it.body.string() } catch (_: Exception) { "" }
            }
        }
        val report = runImport("fake-rt") { invokeSuspend("resolveGeminiRtImportProject", discover); "verified@example.invalid" }
        assertEquals("NETWORK_FAILED", property(items(report).single()!!, "error").toString())
        assertFalse("Remote response read failed before a detached response reached the parser", parserReached)
    }
    @Test fun `actual project failure stays a project failure without secret echo`() = runBlocking {
        val discover: suspend () -> String = { throw IllegalStateException("fake-private-project") }
        val report = runImport("fake-rt") { invokeSuspend("resolveGeminiRtImportProject", discover); "verified@example.invalid" }
        val item = items(report).single()!!
        assertEquals("PROJECT_FAILED", property(item, "error").toString()); assertFalse(item.toString().contains("fake-private-project"))
    }

    private fun client(status: Int = 200, body: String, inspect: (okhttp3.Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
    private suspend fun exchange(client: OkHttpClient): Any =
        invokeSuspend("exchangeGeminiRefreshToken", "fake-original-rt", client, json)!!
    private suspend fun exchangeFailure(client: OkHttpClient): Throwable {
        val error = runCatching { exchange(client) }.exceptionOrNull()
        if (error is AssertionError) throw error
        return error ?: throw AssertionError("Expected exchange rejection")
    }

    @Test fun `RT exchange emits the real Google OAuth refresh request`() = runBlocking {
        var requested = false
        val response = exchange(client(body = """{"access_token":"fake-new-at","expires_in":1800}""") { request ->
            requested = true
            assertEquals(GeminiOAuthManager.TOKEN_URL, request.url.toString()); assertEquals("POST", request.method)
            val form = request.body as FormBody
            val pairs = (0 until form.size).associate { form.name(it) to form.value(it) }
            assertEquals("refresh_token", pairs["grant_type"]); assertEquals("fake-original-rt", pairs["refresh_token"])
            assertEquals(GeminiOAuthManager.CLIENT_ID, pairs["client_id"]); assertEquals(GeminiOAuthManager.CLIENT_SECRET, pairs["client_secret"])
        })
        assertTrue(requested); assertEquals("fake-new-at", property(response, "accessToken")); assertEquals(1800L, property(response, "expiresIn"))
    }
    @Test fun `new refresh token replaces old one when Google rotates it`() = runBlocking {
        val result = exchange(client(body = """{"access_token":"fake-at","refresh_token":"fake-rotated-rt","expires_in":3600}"""))
        assertEquals("fake-rotated-rt", property(result, "refreshToken"))
    }
    @Test fun `missing refresh token preserves the submitted RT`() = runBlocking {
        val result = exchange(client(body = """{"access_token":"fake-at","expires_in":3600}"""))
        assertEquals("fake-original-rt", property(result, "refreshToken"))
    }
    @Test fun `blank refreshed RT also preserves the submitted RT`() = runBlocking {
        val result = exchange(client(body = """{"access_token":"fake-at","refresh_token":"  "}"""))
        assertEquals("fake-original-rt", property(result, "refreshToken"))
    }
    @Test fun `missing or invalid expiry uses safe one hour fallback`() = runBlocking {
        val result = exchange(client(body = """{"access_token":"fake-at","expires_in":-1}"""))
        assertEquals(3600L, property(result, "expiresIn"))
    }
    @Test fun `HTTP failure is explicit without reflecting response secrets`() = runBlocking {
        val failure = exchangeFailure(client(400, """{"error":"invalid_grant","error_description":"fake-private-rt"}"""))
        assertEquals("REFRESH_FAILED", property(failure, "kind").toString()); assertEquals(400, property(failure, "httpStatus"))
        assertFalse(failure.message.orEmpty().contains("fake-private-rt")); assertFalse(failure.message.orEmpty().contains("invalid_grant"))
    }
    @Test fun `malformed successful token response is rejected without body echo`() = runBlocking {
        val failure = exchangeFailure(client(body = "fake-private-rt not JSON"))
        assertEquals("INVALID_TOKEN_RESPONSE", property(failure, "kind").toString()); assertFalse(failure.message.orEmpty().contains("fake-private-rt"))
    }
    @Test fun `successful response without access token is not a signed in account`() = runBlocking {
        val failure = exchangeFailure(client(body = """{"refresh_token":"fake-private-rt"}"""))
        assertEquals("INVALID_TOKEN_RESPONSE", property(failure, "kind").toString())
    }
    @Test fun `blank access token is rejected`() = runBlocking {
        val failure = exchangeFailure(client(body = """{"access_token":" "}"""))
        assertEquals("INVALID_TOKEN_RESPONSE", property(failure, "kind").toString())
    }
    @Test fun `atomic credential write replaces bytes completely`() {
        val file = temporary.newFile("accounts.enc"); file.writeBytes(byteArrayOf(1, 2, 3, 4))
        invoke("writeGeminiCredentialBytesAtomically", file, byteArrayOf(8, 9))
        assertArrayEquals(byteArrayOf(8, 9), file.readBytes())
        assertEquals(listOf("accounts.enc"), temporary.root.listFiles()!!.map { it.name })
    }
    @Test fun `failed atomic replacement leaves existing destination intact`() {
        val directory = temporary.newFolder("accounts.enc"); val old = File(directory, "old-data").apply { writeText("fake-encrypted-old") }
        val failure = runCatching { invoke("writeGeminiCredentialBytesAtomically", directory, byteArrayOf(8, 9)) }.exceptionOrNull()
        if (failure is AssertionError) throw failure
        assertNotNull(failure); assertTrue(directory.isDirectory); assertEquals("fake-encrypted-old", old.readText())
        assertEquals(listOf("accounts.enc"), temporary.root.listFiles()!!.map { it.name })
    }
}
