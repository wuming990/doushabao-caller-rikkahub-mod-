package me.rerere.rikkahub.agent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.agent.model.AgentReport
import me.rerere.rikkahub.agent.model.AgentErrorKind
import me.rerere.rikkahub.agent.model.AgentPipelineStage
import me.rerere.rikkahub.agent.model.AgentRole
import me.rerere.rikkahub.agent.model.AgentSpawnRequest
import me.rerere.rikkahub.agent.model.AgentVerdict
import me.rerere.rikkahub.agent.runtime.AGENT_RESUME_TOTAL_MAX
import me.rerere.rikkahub.agent.runtime.AgentLimitReachedException
import me.rerere.rikkahub.agent.runtime.AgentThreadManager

/**
 * v218：主模型控制子代理的工具（Codex 风格）。v235 起 7 个，v236 起 8 个。
 *
 * 只装配进普通聊天（sendMessage 路径），圆桌不装配（依赖边界测试守护）。
 * 子代理本身拿不到这些工具（禁止递归派发，第一版深度=0）。
 *
 * - spawn_agent：创建独立线程并立即返回 thread_id（不等待）
 * - wait_agents：等待若干线程并返回压缩报告摘要
 * - list_agents：列出本会话的代理线程
 * - send_agent_message：给运行中的代理补充指令
 * - stop_agent：停止单个代理
 * - close_agent：关闭已完成线程（不删除记录）
 * - resume_agent（v235）：让被截断 / 停止 / 中断 / 失败的线程从中断处接着跑。
 *   此前 resume 只有界面按钮能触发，主模型看得见 can_resume 却无法自救，
 *   子代理一遇上游抖动就得停下等人工介入。
 * - **run_agent_pipeline（v236）**：一次性跑完「探测 → 编程 → 审查」整条流水线。
 *   中间过程一个字都不进主对话，主模型只在流水线结束或卡住时才接手 ——
 *   这是用户要的「省额度 + 主模型上下文干净」的最终形态。
 */
fun createAgentControlTools(
    conversationId: String,
    manager: AgentThreadManager,
    workspaceId: String? = null,
    /**
     * v239：取「主对话完整原文」的钩子，签名是 `(includeReasoning, maxChars) -> 文本`。
     *
     * 用户原话：「可以自己选择给不给全部上文，但不能做不到给不了全部上文。」
     * 在此之前 `context_summary` 只能由主模型手打转述 —— 它没有任何办法导出自己的
     * 对话原文，所以「完整上文」这件事在能力上根本不成立。装配方（ChatService）
     * 持有当前对话，由它把原文组装好塞进这个钩子；agent 包这边只收字符串，
     * 因此三向隔离红线不动。
     *
     * 传 null 表示这次装配拿不到对话原文，此时 `include_full_context=true`
     * 会**明确报错**而不是悄悄降级成没有上文 —— 主模型必须知道自己没拿到。
     */
    fullContextProvider: (suspend (Boolean, Int) -> String?)? = null,
): List<Tool> = listOf(
    createSpawnTool(conversationId, manager, workspaceId, fullContextProvider),
    createWaitTool(manager),
    createListTool(conversationId, manager),
    createSendMessageTool(manager),
    createStopTool(manager),
    createCloseTool(manager),
    createResumeTool(conversationId, manager),
    createPipelineTool(conversationId, manager, workspaceId, fullContextProvider),
)

/** v239：没给 `full_context_max_chars` 时用的默认字符上限 */
const val AGENT_FULL_CONTEXT_DEFAULT_MAX_CHARS = 120_000

/** v239：请求了完整上文却拿不到时的错误文案（测试与主模型都靠它辨认） */
const val AGENT_FULL_CONTEXT_UNAVAILABLE =
    "include_full_context was requested but the main conversation text is not available here"

/**
 * v239：算出这次要塞给子代理的上下文。
 *
 * 手写摘要与完整原文**可以同时给**：手写那段放在前面（主模型的划重点），
 * 原文跟在后面。两者都没有就返回 null，与 v238 行为一致。
 */
private suspend fun resolveContextSummary(
    params: kotlinx.serialization.json.JsonObject,
    fullContextProvider: (suspend (Boolean, Int) -> String?)?,
): Result<String?> {
    val manual = params["context_summary"]?.jsonPrimitive?.contentOrNull
        ?.trim()?.takeIf { it.isNotEmpty() }
    val wantFull = params["include_full_context"]?.jsonPrimitive?.contentOrNull
        ?.toBooleanStrictOrNull() ?: false
    if (!wantFull) return Result.success(manual)
    if (fullContextProvider == null) {
        return Result.failure(IllegalStateException(AGENT_FULL_CONTEXT_UNAVAILABLE))
    }
    val includeReasoning = params["include_reasoning"]?.jsonPrimitive?.contentOrNull
        ?.toBooleanStrictOrNull() ?: false
    val maxChars = params["full_context_max_chars"]?.jsonPrimitive?.contentOrNull
        ?.toIntOrNull() ?: AGENT_FULL_CONTEXT_DEFAULT_MAX_CHARS
    val full = runCatching { fullContextProvider(includeReasoning, maxChars) }
        .getOrElse { return Result.failure(it) }
    if (full.isNullOrBlank()) {
        return Result.failure(
            IllegalStateException("$AGENT_FULL_CONTEXT_UNAVAILABLE (nothing to export)")
        )
    }
    return Result.success(if (manual == null) full else manual + "\n\n" + full)
}

/** v239：三个「完整上文」参数的 schema，spawn 与 pipeline 共用一份，避免两处说法不一致 */
private fun kotlinx.serialization.json.JsonObjectBuilder.putFullContextParams() {
    put("include_full_context", buildJsonObject {
        put("type", "boolean")
        put(
            "description",
            "Set true to attach the FULL main conversation transcript (verbatim, including " +
                "your own tool calls and the digests of subagents already spawned in this " +
                "conversation) as the subagent's context. Default false, which keeps the old " +
                "behaviour of passing only context_summary. Costs input tokens on every spawn, " +
                "so use it when the subagent genuinely needs the background.",
        )
    })
    put("full_context_max_chars", buildJsonObject {
        put("type", "integer")
        put(
            "description",
            "Optional character cap for the transcript (default " +
                "$AGENT_FULL_CONTEXT_DEFAULT_MAX_CHARS). When the transcript is longer, the " +
                "middle is dropped and the beginning and end are kept, with a visible note " +
                "saying how much was dropped.",
        )
    })
    put("include_reasoning", buildJsonObject {
        put("type", "boolean")
        put(
            "description",
            "Whether to include model reasoning blocks in the transcript. Default false: " +
                "reasoning is usually longer than the answer itself and rarely helps.",
        )
    })
}

private fun createSpawnTool(
    conversationId: String,
    manager: AgentThreadManager,
    workspaceId: String?,
    fullContextProvider: (suspend (Boolean, Int) -> String?)?,
) = Tool(
    name = "spawn_agent",
    description = "Create a new subagent thread for an independent task and return its thread_id immediately. " +
        "The subagent runs in its own thread with read-only workspace tools (list/find/search/read); " +
        "it cannot see the full main conversation or other agents. Use wait_agents to collect the report. " +
        "Do not exceed the configured concurrency limit (currently ${manager.maxConcurrent}); " +
        "spawn_agent returns an error with max_concurrent when the limit is reached." +
        if (workspaceId.isNullOrBlank()) {
            " NOTE: no workspace is bound to the current assistant, so subagents cannot read any file this time."
        } else {
            ""
        },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task", buildJsonObject {
                    put("type", "string")
                    put("description", "The independent task for the subagent. Must be self-contained.")
                })
                put("role", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional role: default, explorer, reviewer, programmer. " +
                            "explorer = read-only investigation; reviewer = read-only audit that must " +
                            "return verdict pass/fail; programmer = makes the actual edits " +
                            "(needs writable_paths, otherwise it stays read-only).",
                    )
                })
                put("model_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional model id override. Defaults to the current assistant's model.")
                })
                put("context_summary", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional minimal context summary the subagent is allowed to see.")
                })
                put("writable_paths", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Optional whitelist of workspace paths this subagent may modify " +
                            "(files or directory prefixes, e.g. app/src/main/java/foo/Bar.kt). " +
                            "Empty or omitted means READ-ONLY, which is the default. " +
                            "Even with a whitelist the subagent gets no shell, no build and no delete.",
                    )
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("description", "A workspace-relative file path or directory prefix")
                    })
                })
                putFullContextParams()
            },
            required = listOf("task"),
        )
    },
    execute = {
        val params = it.jsonObject
        val task = params["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (task.isBlank()) {
            return@Tool listOf(UIMessagePart.Text("{\"error\":\"task is required\"}"))
        }
        val role = params["role"]?.jsonPrimitive?.contentOrNull?.let { raw ->
            runCatching { AgentRole.valueOf(raw.uppercase()) }.getOrNull()
        } ?: AgentRole.DEFAULT
        // v239：完整上文拿不到就必须明确报错，不能悄悄降级成「没有上文」
        val contextSummary = resolveContextSummary(params, fullContextProvider)
            .getOrElse { error ->
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", error.message ?: AGENT_FULL_CONTEXT_UNAVAILABLE)
                        }.toString()
                    )
                )
            }
        val request = AgentSpawnRequest(
            conversationId = conversationId,
            task = task,
            role = role,
            modelId = params["model_id"]?.jsonPrimitive?.contentOrNull,
            // v220 修复：必须把当前助手绑定的工作区透传给子代理，
            // 否则 createAgentReadOnlyTools 会因 workspaceId 为空返回空工具列表，
            // 子代理将完全读不到任何文件（v219 的真实缺陷）。
            workspaceId = workspaceId,
            contextSummary = contextSummary,
            writablePaths = params.stringList("writable_paths"),
        )
        try {
            val thread = manager.spawn(request)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("thread_id", thread.id)
                        put("status", thread.status.name)
                    }.toString()
                )
            )
        } catch (e: AgentLimitReachedException) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", e.message ?: "agent limit reached")
                        put("max_concurrent", e.maxConcurrent)
                    }.toString()
                )
            )
        }
    },
)

private fun createWaitTool(manager: AgentThreadManager) = Tool(
    name = "wait_agents",
    description = "Wait for one or more agent threads to finish and return their compressed reports. " +
        "Returns current status if timeout is reached.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("thread_ids", buildJsonObject {
                    put("type", "array")
                    put("description", "List of thread ids returned by spawn_agent")
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("description", "A thread id returned by spawn_agent")
                    })
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional timeout in seconds. Defaults to 600.")
                })
            },
            required = listOf("thread_ids"),
        )
    },
    execute = {
        val params = it.jsonObject
        val threadIds = params["thread_ids"]?.jsonArray
            ?.mapNotNull { el -> el.jsonPrimitive.contentOrNull }
            .orEmpty()
        if (threadIds.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text("{\"error\":\"thread_ids is required\"}"))
        }
        val timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull()?.coerceIn(1, 3600) ?: 600L
        val threads = manager.awaitTerminal(threadIds, timeoutSeconds * 1000)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("threads", JsonArray(threads.map { t ->
                        buildJsonObject {
                            val report = AgentReport.decode(t.reportJson)
                            val conclusion = report?.conclusion ?: t.reportJson ?: ""
                            put("thread_id", t.id)
                            put("status", t.status.name)
                            // v234：不再回传任务书全文。任务是主模型自己写的，没必要原样读回来；
                            // 实测 4 个线程查一次进度就白读几千字符的上下文额度。
                            put("task_preview", agentTaskPreview(t.task))
                            put("task_chars", t.task.length)
                            put("conclusion", conclusion)
                            // v235：结构化四段现在会被真正填充（v234 之前三个列表恒为空），
                            // 分字段回传，主线可以只取带文件行号、能机械核对的 evidence，
                            // 而不取主观的 suggestions —— 用廉价模型跑子代理时尤其重要。
                            // 空列表不输出，避免制造噪音。
                            if (report != null) {
                                if (report.evidence.isNotEmpty()) {
                                    put("evidence", JsonArray(report.evidence.map { JsonPrimitive(it) }))
                                }
                                if (report.uncertainties.isNotEmpty()) {
                                    put("uncertainties", JsonArray(report.uncertainties.map { JsonPrimitive(it) }))
                                }
                                if (report.suggestions.isNotEmpty()) {
                                    put("suggestions", JsonArray(report.suggestions.map { JsonPrimitive(it) }))
                                }
                            }
                            // v234：报告是否残缺、还能不能续写，必须让主模型看得见。
                            // 之前只有 status=SUCCEEDED + 一份被砍断的 conclusion，
                            // 主线无法分辨「写完了」和「写到一半被切了」。
                            put("report_chars", conclusion.length)
                            put("truncated", t.truncated)
                            put("can_resume", t.canResume)
                            put("finish_reason", t.finishReason ?: "")
                            put("error", t.error ?: "")
                            // v235：让主模型能判断「等它自动重试」还是「我立刻接手」。
                            // error_kind=RECOVERABLE 时后台可能已经在自动续跑（上限 2 次）；
                            // FATAL 表示再试也没用（Key 无效 / 模型不存在 / 参数非法）。
                            put("resume_count", t.resumeCount)
                            put("resume_limit", AGENT_RESUME_TOTAL_MAX)
                            put("error_kind", AgentErrorKind.classify(t.error).name)
                            // v245：「多久没有新内容」是区分「还在稳定产出」和「已经卡住」的
                            // 唯一硬指标。此前主模型只能看到 status=RUNNING，于是要么白等到
                            // 超时、要么白重派一次（真机踩过）。
                            manager.idleSecondsOrNull(t.id)?.let { idle -> put("idle_seconds", idle) }
                            // v245：它是不是马上会自己接着跑 —— 别把一条正要复活的线程当失败
                            put("auto_resume_planned", manager.isAutoResumePlanned(t.id))
                            // v236：审查位的通过与否，主模型据此决定是否接手
                            report?.verdict?.takeIf { v -> v.isNotBlank() }?.let { v ->
                                put("verdict", v)
                                put("verdict_normalized", AgentVerdict.parse(v).name)
                            }
                            // v236：真正干活的模型（备用模型链可能已经换过人）
                            t.activeModelId?.let { m -> put("active_model_id", m) }
                            // v236：这条线程当时被允许改哪些文件（空 = 只读）
                            if (t.writablePaths.isNotEmpty()) {
                                put("writable_paths", JsonArray(t.writablePaths.map { p -> JsonPrimitive(p) }))
                            }
                        }
                    }))
                    // v245：等待到点但线程还没结束时，必须明说「没完成」并给出判断依据。
                    //
                    // 真机故障：wait_agents 默认只等 600 秒，而「子代理卡住判定」默认也是
                    // 10 分钟 —— 两个数字撞在一起，主模型往往正好在「该换备用模型」的前一刻
                    // 放弃等待，然后把一条还在干活的线程当成失败。
                    val stillRunning = threads.filter { t -> !t.status.isTerminal }
                    if (stillRunning.isNotEmpty()) {
                        put("still_running", stillRunning.size)
                        put(
                            "hint",
                            "还有线程没结束，这不等于失败。看 idle_seconds：数值很小说明它正在产出，" +
                                "直接再调一次 wait_agents 继续等（可以把 timeout_seconds 调大）；" +
                                "只有 idle_seconds 明显超过用户设的「子代理卡住判定」时，才考虑用 " +
                                "resume_agent 换模型或自己接手。auto_resume_planned=true 表示它马上会自己接着跑。"
                        )
                    }
                }.toString()
            )
        )
    },
)

/**
 * v234：任务书预览。
 *
 * `wait_agents` / `list_agents` 原本把 `task` 全文塞回主模型，而任务本来就是主模型
 * 自己写的。本轮实测：4 个子代理、每份任务书 800+ 字符，查一次进度白读三千多字符。
 * 这里只回可辨认的前缀，配合 `task_chars` 让主模型知道原文多长。
 */
private const val AGENT_TASK_PREVIEW_CHARS = 100

private fun agentTaskPreview(task: String): String {
    val oneLine = task.replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= AGENT_TASK_PREVIEW_CHARS) {
        oneLine
    } else {
        oneLine.take(AGENT_TASK_PREVIEW_CHARS) + "…"
    }
}

/**
 * v235：让主模型自己把被中断的子代理接回来。
 *
 * 续跑本身 v222 就实现了（保留中断前全部内容 + 追加续写指令 + 回灌历史），
 * 但入口只挂在界面的「继续输出」按钮上。主模型能从 `wait_agents` 看到
 * `can_resume: true`，却没有任何工具可以触发它 —— 子代理一遇上游抖动，
 * 整条流水线就得停下来等人手点。这个工具补上那个入口。
 *
 * 错误信息刻意分得细：`resume` 返回 null 的原因有好几种（不可续跑 / 已在跑 /
 * 达上限），全都回一句「失败」的话主模型没法决定下一步。
 */
private fun createResumeTool(
    conversationId: String,
    manager: AgentThreadManager,
) = Tool(
    name = "resume_agent",
    description = "Resume a truncated, stopped, interrupted or failed agent thread from where it " +
        "left off. Content produced before the interruption is preserved and not regenerated. " +
        "Use it when wait_agents reports can_resume=true, typically after a recoverable upstream " +
        "error (rate limit, 5xx, connection reset). Fails if the thread cannot be resumed.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("thread_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Target thread id")
                })
                put("extra_instruction", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional extra instruction appended before resuming, e.g. what to focus on.",
                    )
                })
                put("model_id", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional: switch to this model and continue from where it stopped. " +
                            "Unlike a plain resume, this also works on a thread that is still " +
                            "running: it stops the current generation first, keeps everything " +
                            "already produced, then hands the rest to the new model.",
                    )
                })
            },
            required = listOf("thread_id"),
        )
    },
    execute = {
        val threadId = it.jsonObject["thread_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val extra = it.jsonObject["extra_instruction"]?.jsonPrimitive?.contentOrNull
        // v241：带 model_id 就是「换个模型接着跑」，这条路允许对**还在跑**的线程用
        val modelId = it.jsonObject["model_id"]?.jsonPrimitive?.contentOrNull?.trim()
        val switching = !modelId.isNullOrBlank()
        if (threadId.isBlank()) {
            return@Tool listOf(UIMessagePart.Text("{\"error\":\"thread_id is required\"}"))
        }
        val target = manager.list(conversationId).firstOrNull { t -> t.id == threadId }
        val failure = when {
            target == null -> "thread not found in this conversation"
            // v243：换模型时先确认这个模型真的存在，并把原因说清楚。
            // 否则 switchModelAndResume 只返回 null，工具层统一回一句
            // 「already running, or state changed just now」，把真实原因（模型是假的）盖掉 ——
            // 真机实测时这句话确实误导过。
            switching && !manager.isModelAvailable(modelId!!) ->
                "model_id not found in current settings (model=$modelId); " +
                    "nothing was changed, the running attempt was NOT interrupted"

            // 换模型会先把当前那次生成停下来，所以不要求线程已经是终态
            !switching && !target.canResume ->
                "thread is not resumable (status=${target.status.name}, truncated=${target.truncated})"

            target.resumeCount >= AGENT_RESUME_TOTAL_MAX ->
                "resume limit reached (${target.resumeCount}/$AGENT_RESUME_TOTAL_MAX)"

            else -> null
        }
        if (failure != null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", failure)
                        put("thread_id", threadId)
                    }.toString()
                )
            )
        }
        val revived = runCatching {
            if (switching) {
                manager.switchModelAndResume(threadId, modelId!!, extra)
            } else {
                manager.resume(threadId, extra)
            }
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    val thread = revived.getOrNull()
                    when {
                        revived.isFailure -> {
                            put("error", revived.exceptionOrNull()?.message ?: "resume failed")
                            put("thread_id", threadId)
                        }

                        thread == null -> {
                            put(
                                "error",
                                "resume did not start (already running, or state changed just now)",
                            )
                            put("thread_id", threadId)
                        }

                        else -> {
                            put("ok", true)
                            put("thread_id", thread.id)
                            put("status", thread.status.name)
                            put("resume_count", thread.resumeCount)
                        }
                    }
                }.toString()
            )
        )
    },
)

private fun createListTool(
    conversationId: String,
    manager: AgentThreadManager,
) = Tool(
    name = "list_agents",
    description = "List agent threads of this conversation with their status.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { },
            required = emptyList(),
        )
    },
    execute = {
        val threads = manager.list(conversationId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("threads", JsonArray(threads.map { t ->
                        buildJsonObject {
                            put("thread_id", t.id)
                            put("status", t.status.name)
                            put("task_preview", agentTaskPreview(t.task))
                            put("task_chars", t.task.length)
                            put("role", t.role.name)
                            put("truncated", t.truncated)
                            put("can_resume", t.canResume)
                            put("resume_count", t.resumeCount)
                            // v236：一眼看出这条线程有没有写权限
                            put("writable", t.canWrite)
                            // v245：同 wait_agents —— 让主模型自己判断是在干活还是卡住
                            manager.idleSecondsOrNull(t.id)?.let { idle -> put("idle_seconds", idle) }
                        }
                    }))
                }.toString()
            )
        )
    },
)

private fun createSendMessageTool(manager: AgentThreadManager) = Tool(
    name = "send_agent_message",
    description = "Append a follow-up instruction to an agent thread. It is stored and " +
        "takes effect when the thread is re-run; it cannot interrupt a running generation " +
        "(avoiding duplicate billing). Fails if the thread is already finished.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("thread_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Target thread id")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Follow-up instruction")
                })
            },
            required = listOf("thread_id", "content"),
        )
    },
    execute = {
        val params = it.jsonObject
        val threadId = params["thread_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = params["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (threadId.isBlank() || content.isBlank()) {
            return@Tool listOf(UIMessagePart.Text("{\"error\":\"thread_id and content are required\"}"))
        }
        val message = manager.sendMessage(threadId, content)
        if (message == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    "{\"error\":\"thread not found or already finished (no auto re-run): $threadId\"}"
                )
            )
        }
        listOf(UIMessagePart.Text("{\"ok\":true,\"message_id\":\"${message.id}\"}"))
    },
)

private fun createStopTool(manager: AgentThreadManager) = Tool(
    name = "stop_agent",
    description = "Stop a running agent thread. Other agents are not affected. " +
        "It also cancels a pending auto-retry backoff, so the thread cannot come back to life.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("thread_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Target thread id")
                })
            },
            required = listOf("thread_id"),
        )
    },
    execute = {
        val threadId = it.jsonObject["thread_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (threadId.isBlank()) {
            return@Tool listOf(UIMessagePart.Text("{\"error\":\"thread_id is required\"}"))
        }
        manager.stop(threadId)
        listOf(UIMessagePart.Text("{\"ok\":true,\"thread_id\":\"$threadId\"}"))
    },
)

private fun createCloseTool(manager: AgentThreadManager) = Tool(
    name = "close_agent",
    description = "Close a finished agent thread. Records are kept. Fails if the thread is still running.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("thread_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Target thread id")
                })
            },
            required = listOf("thread_id"),
        )
    },
    execute = {
        val threadId = it.jsonObject["thread_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (threadId.isBlank()) {
            return@Tool listOf(UIMessagePart.Text("{\"error\":\"thread_id is required\"}"))
        }
        val closed = manager.close(threadId)
        listOf(
            UIMessagePart.Text(
                if (closed) "{\"ok\":true,\"thread_id\":\"$threadId\"}"
                else "{\"error\":\"thread not found or still running: $threadId\"}"
            )
        )
    },
)

/**
 * v236：一次调用跑完整条子代理流水线。
 *
 * ## 为什么需要它
 *
 * 用户的原话是：
 * > 「换成对话主模型负责发布任务和最后审查，一个或者多个负责探测（子代理），
 * >  一个负责编程（固定位置子代理），一个负责一审和改错（子代理），
 * >  如果不通过，进入主代理接手后续工作。省额度的同时还可以让主模型上下文干净。」
 *
 * v235 只有 spawn / wait / resume 这些**手动**工具：每一棒都要主模型自己派、自己等、
 * 自己把上一棒的报告读进来再转述给下一棒。中转本身就在烧主模型的上下文，
 * 「上下文干净」这半个目标其实没达成。
 *
 * 这个工具把整条链交给 [AgentThreadManager.runPipeline]：阶段之间的报告在
 * AgentDatabase 里直接转交，一个字都不进主对话。主模型只在两种时候接手 ——
 * 全部跑完，或者某一棒不通过/失败/卡住。
 *
 * ## 刻意不做的事
 *
 * - **不给编译权限**：编译独占 Gradle、单次好几分钟，只能留在主模型手里。
 *   流水线内的机械裁判是审查位（gate），编译由主模型在流水线之后自己跑。
 * - **不并行**：流水线是有先后依赖的接力（探测的结论要给编程位用），
 *   并行没有意义。要并行探测请直接用 spawn_agent 派多个 explorer。
 */
private fun createPipelineTool(
    conversationId: String,
    manager: AgentThreadManager,
    workspaceId: String?,
    fullContextProvider: (suspend (Boolean, Int) -> String?)?,
) = Tool(
    name = "run_agent_pipeline",
    description = "Run a sequential subagent pipeline in one call and return only the summary. " +
        "Each stage receives the previous stage's conclusion and evidence automatically, so no " +
        "intermediate output enters the main conversation. Typical shape: " +
        "explorer (read-only) -> programmer (writable whitelist) -> reviewer (gate=true). " +
        "The pipeline stops and hands control back to you when a gate stage does not return " +
        "verdict=pass, or when a stage fails, is stopped, or times out. " +
        "Subagents never get a shell and never compile: run the build and the tests yourself " +
        "after the pipeline returns." +
        if (workspaceId.isNullOrBlank()) {
            " NOTE: no workspace is bound to the current assistant, so subagents cannot read or write any file."
        } else {
            ""
        },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("stages", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Ordered stages. Each item: {task (required), role, model_id, " +
                            "writable_paths (array), gate (boolean)}. " +
                            "Set gate=true on a reviewer stage so its verdict decides whether to continue.",
                    )
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("description", "One pipeline stage")
                    })
                })
                put("stage_timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional per-stage wait limit in seconds. Defaults to 900.")
                })
                put("context_summary", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional context handed to every stage, in addition to the previous " +
                            "stage's report which is passed automatically.",
                    )
                })
                putFullContextParams()
            },
            required = listOf("stages"),
        )
    },
    execute = {
        val params = it.jsonObject
        val rawStages = params["stages"]?.jsonArray.orEmpty()
        // v239：整条流水线共用一份上下文（顶层参数决定），组装一次给所有阶段用。
        // 拿不到就直接报错回主模型，不悄悄降级。
        val sharedContext = resolveContextSummary(params, fullContextProvider)
            .getOrElse { error ->
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", error.message ?: AGENT_FULL_CONTEXT_UNAVAILABLE)
                        }.toString()
                    )
                )
            }
        val stages = rawStages.mapNotNull { element ->
            val obj = element.jsonObject
            val task = obj["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (task.isBlank()) return@mapNotNull null
            AgentPipelineStage(
                task = task,
                role = obj["role"]?.jsonPrimitive?.contentOrNull
                    ?.let { raw -> runCatching { AgentRole.valueOf(raw.uppercase()) }.getOrNull() }
                    ?: AgentRole.DEFAULT,
                modelId = obj["model_id"]?.jsonPrimitive?.contentOrNull,
                writablePaths = obj.stringList("writable_paths"),
                gate = obj["gate"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                contextSummary = sharedContext,
            )
        }
        if (stages.isEmpty()) {
            return@Tool listOf(
                UIMessagePart.Text("{\"error\":\"stages is required and every stage needs a task\"}")
            )
        }
        val timeoutMillis = (params["stage_timeout_seconds"]?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull()?.coerceIn(30, 3600) ?: 900L) * 1000L

        val outcome = manager.runPipeline(
            conversationId = conversationId,
            workspaceId = workspaceId,
            stages = stages,
            stageTimeoutMillis = timeoutMillis,
        )

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("completed", outcome.completed)
                    put("reason", outcome.reason)
                    put("detail", outcome.detail)
                    put("stage_count", stages.size)
                    put("stopped_at_stage", outcome.stoppedAtIndex + 1)
                    put("stages", JsonArray(outcome.threads.mapIndexed { index, thread ->
                        buildJsonObject {
                            val report = AgentReport.decode(thread.reportJson)
                            put("stage", index + 1)
                            put("thread_id", thread.id)
                            put("role", thread.role.name)
                            put("status", thread.status.name)
                            put("conclusion", report?.conclusion ?: thread.reportJson ?: "")
                            if (report != null && report.evidence.isNotEmpty()) {
                                put("evidence", JsonArray(report.evidence.map { e -> JsonPrimitive(e) }))
                            }
                            report?.verdict?.takeIf { v -> v.isNotBlank() }?.let { v ->
                                put("verdict", v)
                                put("verdict_normalized", AgentVerdict.parse(v).name)
                            }
                            put("truncated", thread.truncated)
                            put("can_resume", thread.canResume)
                            put("writable", thread.canWrite)
                            thread.activeModelId?.let { m -> put("active_model_id", m) }
                            thread.error?.takeIf { e -> e.isNotBlank() }?.let { e -> put("error", e) }
                        }
                    }))
                    put(
                        "next_step_hint",
                        if (outcome.completed) {
                            "All stages passed. Now run the build and the tests yourself, then decide."
                        } else {
                            "Pipeline stopped. Read the failing stage above and take over from there."
                        },
                    )
                }.toString()
            )
        )
    },
)

/** v236：读取字符串数组参数（模型偶尔会塞单个字符串进来，这里一并容忍） */
private fun kotlinx.serialization.json.JsonObject.stringList(name: String): List<String> {
    val element = this[name] ?: return emptyList()
    val fromArray = runCatching {
        element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
    }.getOrNull()
    if (fromArray != null) return fromArray.filter { it.isNotEmpty() }
    val single = runCatching { element.jsonPrimitive.contentOrNull?.trim() }.getOrNull()
    return listOfNotNull(single?.takeIf { it.isNotEmpty() })
}
