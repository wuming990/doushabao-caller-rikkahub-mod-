package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * v1027：**子代理专用**的 MCP 工具枚举 —— 返回符合条件的**全部工具配置记录，含被禁用的那些**。
 *
 * 🔴 为什么不能复用 McpManager.getAllAvailableTools：那个方法在返回之前就做了
 * `.filter { tool -> tool.enable }`。于是同一身份（serverId + 原始工具名）若存在
 * 「禁用 + needsApproval=true」和「启用 + needsApproval=false」两份配置，
 * 前一份根本到不了子代理的决策函数，决策函数里「任一配置要求批准 → 整个身份都不挂」
 * 这条保护就成了空话 —— 后一份会照常挂上并以无审批状态调用外部服务。
 * 第七轮双审把这条列为**越权入口 4**（前三次都是「归并之前先过滤」，这一次过滤发生在
 * 更上游的枚举里，函数内部改得再对也救不回来）。
 *
 * 所以安全判断必须拿到同一个身份的**完整**配置集，工具级 enable 交给决策函数连同审批
 * 一起判（它的 a→b→c 顺序已经处理了「全部禁用 → 不挂也不计入原因」这种情况）。
 *
 * 仍然保留两道真实边界，不是无条件放宽：服务器本身必须 enable=true，
 * 且必须在助手/会话勾选的 id 集合里。
 *
 * 抽成顶层纯函数是为了能被**真的测到**：McpManager 需要 SettingsStore/AppScope/FilesManager
 * 才能实例化，而这条枚举只依赖入参。接线由测试断言保证生产路径确实调用它。
 */
/**
 * 子代理枚举的结果。两个字段都必须如实带出去，不许只返回记录列表：
 *
 * - entries：可以进入决策流程的记录
 * - ambiguousServerCount：**因为同一 serverId 出现多份配置而被整台放弃的服务器数量**
 *
 * ⚠️ 量纲与工具级计数不同，这里是「台」不是「个工具」，说明书必须分开讲。
 */
internal data class AgentMcpToolRecords(
    val entries: List<Triple<Uuid, String, McpTool>>,
    val ambiguousServerCount: Int = 0,
)

internal fun agentMcpToolRecords(
    servers: List<McpServerConfig>,
    selectedServerIds: Set<Uuid>,
): AgentMcpToolRecords {
    // 🔴 第八轮审查抓到**越权入口 5**：同一个 serverId 出现多份配置时，
    // 连接层 McpSessionRegistry.reconcile 用 `.associateBy { it.id }` 建会话表，
    // 重复 key 后者覆盖前者，而执行又是 `sessions[serverId]` 按 id 取会话；
    // 决策函数取代表用的却是列表里**第一份**符合条件的记录。
    // 合起来：模型看到的服务器名与参数 schema 来自第一份，实际调用打到最后一份 ——
    // **等于把外部操作发往另一台服务器**。这比「该问用户的没问」更严重，
    // 也正是当初否掉「名字超长就截断」的同一条理由（不能赌两份配置其实一样）。
    // 所以同一 id 只要出现多份配置，这台服务器一个工具都不提供，并把台数报出去。
    //
    // 🔴 第八轮补审又抓到**越权入口 6**，是同一个洞的第六次现身，而且就藏在我为入口 5
    // 写的这段代码里：判重原本写在「服务器级 enable 与勾选过滤」之后。于是
    // 「同 id 两份配置，一份整台停用且其工具 needsApproval=true、另一份启用且同名工具
    // needsApproval=false」时，停用那份在判重之前就消失了，入口 5 的检测根本看不到它，
    // 结果与入口 4 完全同构：审批要求被洗掉，工具以无审批状态执行。
    //
    // 教训已经重复六次，写成死规矩：**判重与归并必须在任何过滤之前**。
    // 顺序是 ①按 id 判重（看全部配置）→ ②剔除有歧义的整台 → ③才轮到 enable 与勾选。
    val ambiguous = servers.groupBy { it.id }.filterValues { it.size > 1 }.keys
    val chosen = servers
        .filter { it.id !in ambiguous }
        .filter { it.commonOptions.enable && it.id in selectedServerIds }
    return AgentMcpToolRecords(
        entries = chosen.flatMap { server ->
            server.commonOptions.tools.map { tool -> Triple(server.id, server.commonOptions.name, tool) }
        },
        // 剔除针对**全部**重复 id（安全），但计数只报与本次勾选有关的那些：
        // 否则一台用户压根没勾的服务器有重复配置，也会跑进子代理说明书里
        // 说成「你的工具为什么用不了」，那是无关报告（第九轮审查指出）。
        ambiguousServerCount = ambiguous.count { it in selectedServerIds },
    )
}

/**
 * v1027：**执行之前**的实时复查。返回 null 表示可以放心调用外部服务器；
 * 返回一段文字表示本次拒绝执行，那段文字会作为工具结果交给模型看。
 *
 * 🔴 第九轮审查抓到的**入口 7（时序版）**：判重与勾选只在装配那一刻用快照判过一次，
 * 而 MCP 工具真正执行往往是几分钟之后，走的还是**实时**会话表 ——
 * `McpSessionRegistry.reconcile` 用 `.associateBy { it.id }`（重复 id 后者覆盖），
 * `callTool` 用 `sessions[serverId]`。所以子代理运行的这几分钟里，只要用户
 * 新增一份同 id 配置、把服务器或工具停用、删掉配置，或者把某个工具改成「需要批准」，
 * 装配时那些判断就全部过期：模型手里的名字与参数说明还是旧的，
 * 而调用会打到新的那一份上，或者把一个「现在要求用户批准」的工具直接执行掉。
 * 后面这一条与入口 1~6 后果完全相同 —— 替用户放权，只是触发方式从「少给数据」
 * 变成「数据后来变了」。
 *
 * liveSelectedServerIds 传 null 表示**不检查勾选维度**：
 * 当勾选来自这个对话自己的 config 时，不查库就没法实时拿到它，
 * 硬拿助手上的旧勾选去比会误拒正常配置。这个残余已如实登记进 PROJECT-MANUAL §5.9
 * （「执行前复查的已知残余」第①条），不谎称全防。
 * 其余六项（存在、唯一、服务器启用、工具存在、工具启用、审批标志）
 * 都只用服务器清单判断，零误拒风险。
 *
 * 总开关（allowAgentMcp）**故意不在这里判**：它不依赖服务器清单，
 * 由 `GenerationAgentBackend.runAgentMcpToolGuarded` 的第一句负责 ——
 * 撤权优先于一切细则，而且必须取实时值（`live.allowAgentMcp`），不能用 run() 开头的旧快照。
 *
 * 同名多条记录（同一台服务器里出现两条同名工具）按集合判，口径与装配层一致：
 * 任一条要求批准就拒绝，全部禁用才算出「已停用」。不看第一条，避免「装配层不肯挂、
 * 执行期放行」这种反向漏洞（第十一轮双审提出）。
 */
internal fun agentMcpStalenessNote(
    servers: List<McpServerConfig>,
    serverId: Uuid,
    toolName: String,
    liveSelectedServerIds: Set<Uuid>? = null,
): String? {
    val matches = servers.filter { it.id == serverId }
    val shortId = serverId.toString().take(8)
    if (matches.isEmpty()) {
        return "MCP 工具本次没有执行：这台服务器（id $shortId）的配置已经从设置里消失了。" +
            "装配时它还在，现在不在了 —— 请把这个情况如实写进报告，不要重试。"
    }
    if (matches.size > 1) {
        return "MCP 工具本次没有执行：这台服务器（id $shortId）现在有 ${matches.size} 份相同 id 的配置，" +
            "而连接层只认其中一份 —— 继续调用可能打到不是你以为的那台服务器。" +
            "请把重复的配置删掉再重跑，不要重试。"
    }
    val cfg = matches.first()
    if (!cfg.commonOptions.enable) {
        return "MCP 工具本次没有执行：服务器「${cfg.commonOptions.name}」已被停用（装配之后关掉的）。" +
            "请写进报告，不要重试。"
    }
    if (liveSelectedServerIds != null && serverId !in liveSelectedServerIds) {
        return "MCP 工具本次没有执行：这台服务器（id $shortId）已经不在勾选列表里了（装配之后被取消）。" +
            "请写进报告，不要重试。"
    }
    // 🔴 第十一轮双审：同名多条记录时，执行期口径必须与装配层在**放行/拒绝集合**上一致。
    // 装配层（decideAgentMcpTools）是「全部禁用 → 不挂」「任一条要求批准 → 不挂」；
    // 这里若用 firstOrNull 只看第一条，就可能出现「装配层不肯挂、执行期放行」，
    // 或反过来把一条正常工具误报成停用。所以改成按同名集合判。
    // 执行层这里**先判审批、再判全部停用**：两者在放行/拒绝集合上等价（只要有一条要批准就不调、
    // 全部停用才算出停用），但文案归属会差一点 —— 同名两条时优先报审批，因为那是更严重的
    // 拦截理由，用户更需要先看到。这不新增限制 —— 装配层本来就不会挂需要批准的工具。
    val sameName = cfg.commonOptions.tools.filter { it.name == toolName }
    if (sameName.isEmpty()) {
        return "MCP 工具本次没有执行：工具「$toolName」在这台服务器的配置里已经找不到了" +
            "（可能被重新同步过或已改名）。请写进报告，不要重试。"
    }
    if (sameName.any { it.needsApproval }) {
        return "MCP 工具本次没有执行：工具「$toolName」现在被设为**需要用户手动批准**，" +
            "而子代理没有批准入口 —— 装配之后这条保护才被打开。" +
            "请把这件事写进 uncertainties 交回主对话由用户本人批准，绝对不要重试。"
    }
    if (sameName.none { it.enable }) {
        return "MCP 工具本次没有执行：工具「$toolName」已被停用（装配之后关掉的）。请写进报告，不要重试。"
    }
    return null
}

/**
 * MCP 子系统的公共入口。
 *
 * 这里仅协调配置、OAuth、连接注册表与 UI 内容转换；单个服务器的连接状态机由
 * [McpSessionRegistry] 管理，OAuth 协议细节由 [McpOAuthCoordinator] 管理。
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    private val statusStore = McpStatusStore()
    private val oauthCallbackServer = OAuthLoopbackCallbackServer(
        port = MCP_OAUTH_CALLBACK_PORT,
        callbackPath = MCP_OAUTH_CALLBACK_PATH,
    )
    private val oauthCoordinator = McpOAuthCoordinator(
        settingsStore = settingsStore,
        appScope = appScope,
        oauthClient = OAuthHttpClient(okHttpClient),
        discoveryClient = McpOAuthDiscoveryClient(okHttpClient),
        callbackServer = oauthCallbackServer,
        authorizationLauncher = CustomTabsOAuthAuthorizationLauncher,
        updateStatus = statusStore::update,
    )
    private val sessionRegistry = McpSessionRegistry(
        settingsStore = settingsStore,
        appScope = appScope,
        httpClient = httpClient,
        oauthCoordinator = oauthCoordinator,
        statusStore = statusStore,
    )

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .distinctUntilChanged()
                .collect(sessionRegistry::reconcile)
        }
    }

    val syncingStatus: StateFlow<Map<Uuid, McpStatus>>
        get() = statusStore.status

    fun getClient(config: McpServerConfig): Client? = sessionRegistry.getClient(config.id)

    fun getStatus(config: McpServerConfig): Flow<McpStatus> = sessionRegistry.getStatus(config.id)

    fun getAllAvailableTools(assistant: Assistant): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        return settings.mcpServers
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        val result = try {
            sessionRegistry.callTool(serverId, toolName, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpClientUnavailableException) {
            return listOf(UIMessagePart.Text("Failed to execute MCP tool: ${e.message ?: e.javaClass.name}"))
        }
        return result.content.map { content ->
            when (content) {
                is TextContent -> UIMessagePart.Text(content.text)
                is ImageContent -> convertImageContentToFilePart(content)
                else -> UIMessagePart.Text(JsonInstant.encodeToString(content))
            }
        }
    }

    suspend fun addClient(config: McpServerConfig) = sessionRegistry.addClient(config)

    suspend fun removeClient(config: McpServerConfig) = sessionRegistry.removeClient(config)

    suspend fun syncAll() = sessionRegistry.syncAll()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        sessionRegistry.addClient(freshConfig)
    }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val extension = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$extension",
            mimeType = image.mimeType,
        )
        return UIMessagePart.Image(url = filesManager.getFile(entity).toUri().toString())
    }
}
