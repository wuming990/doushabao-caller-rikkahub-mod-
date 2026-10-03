package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.providerHostOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.gemini.GeminiSearchClient
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

/**
 * v310：模型属于「谷歌账号登录」通道、且模型开了内置搜索时，把搜索装配成客户端工具
 * （独立请求，见 [GeminiSearchClient]）。这条通道上 googleSearch 不能与函数工具同请求，
 * 所以主请求里不再出现它 —— 搜索与工作区工具从此可以共存。
 * 其它通道（含官方 API key 版 Google）行为一字不变。
 */
internal fun shouldUseGeminiNativeSearch(provider: ProviderSetting?, model: Model): Boolean {
    return provider is ProviderSetting.GeminiOAuth && BuiltInTools.Search in model.tools
}

/**
 * v310：搜索工具的装配决策（**单一来源**）。
 *
 * 为什么必须有这个函数：主对话的**真实**工具表在 ChatService 的内联 buildList 里
 * （`chatToolFactory.createTools(...)` 的返回值在主对话路径上只用于 MCP 名校验、
 * 不参与装配），两处若各写一份搜索分支必然漂移。主对话与 [ChatToolFactory] 统一调用本函数。
 *
 * 规则：
 * - 谷歌账号通道 + 模型开了内置搜索 → 装独立搜索工具（google_search）；
 * - 其它情况沿用原逻辑（助手开了联网搜索且模型没有内置搜索 → Exa 等外部搜索工具）；
 * - 都没有 → 一个搜索工具都不装。
 */
internal fun createSearchToolsForGeneration(
    settings: Settings,
    assistant: Assistant,
    model: Model,
    geminiSearchClient: GeminiSearchClient,
): List<Tool> {
    val provider = model.findProvider(settings.providers)
    return when {
        shouldUseGeminiNativeSearch(provider, model) ->
            listOf(createGeminiNativeSearchTool(geminiSearchClient))

        shouldUseExternalWebSearch(assistant, model) ->
            createSearchTools(settings).toList()

        else -> emptyList()
    }
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val geminiSearchClient: GeminiSearchClient,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
    ): List<Tool> = buildList {
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            addAll(
                buildMemoryTools(
                    json = json,
                    onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content) },
                    onUpdate = { id, content -> memoryRepository.updateContent(id, content) },
                    onDelete = { id -> memoryRepository.deleteMemory(id) },
                )
            )
        }
        addAll(createSearchToolsForGeneration(settings, assistant, model, geminiSearchClient))
        // v300：把本次生成用的供应商/模型告诉本地工具装配 ——
        // 商汤 + kimi 组合不提供 ask_user（选项会被网关吃掉，用户只会被卡住）
        addAll(localTools.getTools(assistant.localTools, providerHostOf(model, settings), model.modelId))
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id))
        }
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    forceReview = settings.displaySetting.forceSkillReview,
                )
            )
        }

        val mcpTools = mcpManager.getAllAvailableTools()
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        mcpTools.forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
