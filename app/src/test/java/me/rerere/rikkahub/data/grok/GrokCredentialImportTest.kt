package me.rerere.rikkahub.data.grok

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * v282：Grok 凭据导入的解析门禁。
 *
 * 背景：用户手上一批 Grok 账号只有「提取工具」导出的凭据文件，没有账号密码，所以走不了
 * 设备码登录（那个流程要在网页上点批准），只能把凭据搬进来。同一个工具的两种导出格式字段名
 * 并不一致（真机两份文件实测）：
 * - 一份把用户 id 写在 `sub`、过期时刻写在 `expired`，`user_id` 缺失；
 * - 另一份把用户 id 写在 `user_id`、过期时刻写在 `expires_at`，而 `sub` 是空字符串。
 *
 * 所以解析一律「哪个字段有值用哪个」，不能按固定字段名读 —— 这批测试就钉这件事。
 */
class GrokCredentialImportTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val now = 1_700_000_000_000L

    /** 造一个形状真实的假 JWT：payload 放 claim，签名段随便填（解析不校验签名）。 */
    private fun fakeAccessToken(sub: String = "", email: String = ""): String {
        val claims = buildString {
            append("{")
            append("\"token_use\":\"access\"")
            if (sub.isNotEmpty()) append(",\"sub\":\"$sub\"")
            if (email.isNotEmpty()) append(",\"email\":\"$email\"")
            append("}")
        }
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims.toByteArray())
        return "eyJhbGciOiJFUzI1NiJ9.$payload.signature"
    }

    private fun parse(raw: String) =
        parseGrokCredentialImport(json.parseToJsonElement(raw), json, now)

    // ---- 三种文件形态 ----

    @Test
    fun `批量导出格式：一个文件里的多个账号一次全部导入`() {
        val file = """
            {
              "provider": "build",
              "accounts": [
                {
                  "provider": "grok_build",
                  "name": "one@example.com",
                  "access_token": "${fakeAccessToken()}",
                  "refresh_token": "refresh-1",
                  "email": "one@example.com",
                  "sub": "",
                  "user_id": "user-1",
                  "expires_at": "2026-09-04T15:16:38.542052502Z"
                },
                {
                  "provider": "grok_build",
                  "name": "two@example.com",
                  "access_token": "${fakeAccessToken()}",
                  "refresh_token": "refresh-2",
                  "email": "two@example.com",
                  "user_id": "user-2",
                  "expires_at": "2026-09-04T15:16:38Z"
                }
              ]
            }
        """.trimIndent()

        val parsed = parse(file)
        assertEquals(2, parsed.credentials.size)
        assertEquals(0, parsed.skipped)
        assertEquals(listOf("user-1", "user-2"), parsed.credentials.map { it.userId })
        assertEquals(listOf("refresh-1", "refresh-2"), parsed.credentials.map { it.refreshToken })
        assertEquals(listOf("one@example.com", "two@example.com"), parsed.credentials.map { it.email })
        // name 字段本身就是邮箱时，账号名取邮箱前缀，别在列表里显示一长串邮箱
        assertEquals(listOf("one", "two"), parsed.credentials.map { it.name })
    }

    @Test
    fun `单账号格式：顶层就是账号本身`() {
        val file = """
            {
              "type": "xai",
              "auth_kind": "oauth",
              "email": "solo@example.com",
              "sub": "user-solo",
              "access_token": "${fakeAccessToken()}",
              "refresh_token": "refresh-solo",
              "expired": "2026-09-04T15:16:38Z",
              "base_url": "https://cli-chat-proxy.grok.com/v1"
            }
        """.trimIndent()

        val parsed = parse(file)
        assertEquals(1, parsed.credentials.size)
        // 这份格式把用户 id 放在 sub、过期时刻放在 expired —— 两个字段名都必须认
        assertEquals("user-solo", parsed.credentials.single().userId)
        assertEquals("solo@example.com", parsed.credentials.single().email)
    }

    @Test
    fun `裸数组也认`() {
        val file = """
            [
              {
                "access_token": "${fakeAccessToken()}",
                "refresh_token": "r1",
                "email": "a@example.com",
                "user_id": "u1"
              }
            ]
        """.trimIndent()
        assertEquals(1, parse(file).credentials.size)
    }

    @Test
    fun `完全认不出的结构不会崩，只是什么都没有`() {
        assertEquals(0, parse("\"just a string\"").credentials.size)
        assertEquals(0, parse("123").credentials.size)
        assertEquals(0, parse("{\"accounts\":[]}").credentials.size)
    }

    // ---- 缺钥匙的条目 ----

    @Test
    fun `缺 access_token 或 refresh_token 的条目被跳过并计数`() {
        val file = """
            {
              "accounts": [
                { "access_token": "${fakeAccessToken()}", "refresh_token": "ok", "user_id": "u-ok" },
                { "access_token": "${fakeAccessToken()}", "user_id": "u-no-refresh" },
                { "refresh_token": "only-refresh", "user_id": "u-no-access" },
                { "access_token": "  ", "refresh_token": "  ", "user_id": "u-blank" }
              ]
            }
        """.trimIndent()

        val parsed = parse(file)
        assertEquals("只有第一条是完整的", 1, parsed.credentials.size)
        assertEquals("u-ok", parsed.credentials.single().userId)
        assertEquals("另外三条必须计入跳过数，不能悄悄丢掉", 3, parsed.skipped)
    }

    // ---- 过期时刻 ----

    @Test
    fun `过期时刻：未来照用，过去和读不出来一律归零`() {
        // 归零的含义是「当作已过期」，让账号在第一次使用前先用 refresh_token 换一把新钥匙。
        val future = "2026-09-04T15:16:38Z"
        val past = "2020-01-01T00:00:00Z"

        assertTrue(parseGrokImportExpiry(future, now) > now)
        assertEquals(0L, parseGrokImportExpiry(past, now))
        assertEquals(0L, parseGrokImportExpiry("", now))
        assertEquals(0L, parseGrokImportExpiry("   ", now))
        assertEquals(0L, parseGrokImportExpiry("not a time", now))
        // 带纳秒精度的写法（真机文件就是这种）必须能解析
        assertTrue(parseGrokImportExpiry("2026-09-04T15:16:38.542052502Z", now) > now)
    }

    @Test
    fun `导出文件里没写过期时刻时也能导入`() {
        val file = """
            {
              "access_token": "${fakeAccessToken()}",
              "refresh_token": "r",
              "user_id": "u"
            }
        """.trimIndent()
        val credential = parse(file).credentials.single()
        assertEquals(0L, credential.expiresAtMillis)
    }

    // ---- 身份兜底 ----

    @Test
    fun `文件里没写身份时从令牌里取`() {
        val file = """
            {
              "access_token": "${fakeAccessToken(sub = "jwt-user", email = "jwt@example.com")}",
              "refresh_token": "r"
            }
        """.trimIndent()
        val credential = parse(file).credentials.single()
        assertEquals("jwt-user", credential.userId)
        assertEquals("jwt@example.com", credential.email)
        assertEquals("jwt", credential.name)
    }

    @Test
    fun `文件里写明的身份优先于令牌里的`() {
        // 真机文件的令牌 payload 里只有 sub、没有 email；身份必须以字段为准，
        // 否则账号列表里全是没法分辨的「Grok」。
        val file = """
            {
              "access_token": "${fakeAccessToken(sub = "jwt-user", email = "jwt@example.com")}",
              "refresh_token": "r",
              "user_id": "field-user",
              "email": "field@example.com"
            }
        """.trimIndent()
        val credential = parse(file).credentials.single()
        assertEquals("field-user", credential.userId)
        assertEquals("field@example.com", credential.email)
    }

    @Test
    fun `什么身份都没有也不会得到空名字`() {
        val file = """
            { "access_token": "not-a-jwt", "refresh_token": "r" }
        """.trimIndent()
        val credential = parse(file).credentials.single()
        assertNotNull(credential.name)
        assertTrue("名字不能是空的，否则账号列表里是一行空白", credential.name.isNotBlank())
    }

    // ---- 导入结果的账目 ----

    @Test
    fun `导入结果的总数是新增加更新，不含跳过`() {
        assertEquals(3, GrokImportResult(imported = 2, updated = 1, skipped = 5).total)
        assertEquals(0, GrokImportResult(imported = 0, updated = 0, skipped = 4).total)
    }
}
