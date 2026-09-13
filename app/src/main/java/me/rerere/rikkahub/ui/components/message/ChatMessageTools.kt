package me.rerere.rikkahub.ui.components.message

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.askUserArgumentsUnreadable
import me.rerere.rikkahub.data.ai.tools.local.parseAskUserQuestions
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant

private const val ASK_USER_TOOL_NAME = "ask_user"

// v295：诊断日志 tag（ask_user 卡片解析异常/选项缺失的第一现场）
private const val TAG = "AskUserCard"

@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val loading = !tool.isFinished
    ChainOfThoughtStep(
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_call_generic, tool.toolName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = loading,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.isPending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else {
            null
        },
        onClick = if (context.content != null || isPending || images.isNotEmpty()) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                renderer.Preview(
                    context = context,
                    onDismissRequest = { showResult = false },
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // v297：解析换成顶层纯函数（逐题、逐选项容错，真单测见 AskUserParsingTest）。
    // 旧实现是「整批 runCatching + 对每个选项硬取 jsonPrimitive」，一个选项形状不对
    // 就让整张卡片的所有题目一起消失（用户只看到一个省略号，而提交键照样能点）。
    //
    // 诊断日志**不能**写在 remember 的计算块里：remember 在每次重组时都可能重放计算，
    // 会把含用户业务内容的参数原文一遍遍刷进 logcat（审查位抓到）。
    // 这里只缓存解析结果，日志交给 LaunchedEffect(arguments) —— 参数真的变了才打一次。
    val questions = remember(arguments) { parseAskUserQuestions(arguments) }
    // v297：参数里确实有内容、却一道题都没读出来 —— 卡片必须仍然可回答，不许留空白
    val unreadable = remember(arguments) { askUserArgumentsUnreadable(arguments) }
    LaunchedEffect(arguments) {
        // v299：诊断日志同时写进 App 内日志页（Logging.log）。
        // 只写 Log.w 时只有插电脑看 logcat 才看得到 —— 用户手上拿不到证据，
        // 商汤渠道「选项一个都显示不出来」就一直只能猜。设置→日志 现在能直接看到并复制。
        if (unreadable) {
            val msg = "ask_user 未解析出任何问题（参数形状不符），已改用自由文本回答；原始参数片段: $arguments".take(400)
            Log.w(TAG, msg)
            Logging.log(TAG, msg)
        }
        // v295：single/multi 缺 options 是「真机卡死」的上游现场（商汤渠道实遇），留证据
        // v299：条件与卡片摊原文保持一致 —— 纯文本题（schema 明确允许不带 options）是正常形态，
        // 不该每次打开对话都记一条「选项缺失」把 100 条环形缓冲刷满（二轮审查位指出）。
        if (questions.any { q -> q.options.isEmpty() && (q.selectionType != "text" || q.hadOptionsField) }) {
            val msg = "ask_user 本该有选项却没解析出来（已降级文本输入）；原始参数片段: $arguments".take(400)
            Log.w(TAG, msg)
            Logging.log(TAG, msg)
        }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }
    // v297：一道题都没解析出来时的兜底回答
    var freeTextAnswer by remember { mutableStateOf("") }

    val firstQuestion = questions.firstOrNull()?.question
        ?: if (unreadable) stringResource(R.string.chat_message_tool_ask_unreadable) else "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // v297：一道题都读不出来时的兜底 —— 显示原始参数片段 + 自由文本框。
                // 旧版这里是一片空白（用户看不到问题、也没有回答途径），
                // 而提交键的「空列表 all 判定恒真」照样放行，模型只收到空 answers。
                if (questions.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (unreadable) {
                            Text(
                                text = arguments.toString().take(200),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isPending && onToolAnswer != null) {
                            OutlinedTextField(
                                value = freeTextAnswer,
                                onValueChange = { freeTextAnswer = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                            )
                        } else if (isAnswered) {
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            Text(
                                text = answeredState.answer.take(300),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            // 官方 2.5.1：不再「单选/多选只给选项、不给输入框」——
                            // 每道题都能自己手打答案，多选还能把选项和自定义文字一起交。
                            // 单选/多选即使一个选项都没有，也自然降级成纯文本，不会再卡死。
                            //
                            // v295 备忘（旧写法已由官方这版取代，教训保留）：
                            // 上游可能不生成 options；旧版「单选/多选只给选项」时，选项一丢用户
                            // 就没有任何回答途径、提交键永远灰着（商汤渠道真机卡死）。
                            // 现在文本框恒在，等价于旧版那几处「选项为空就降级」的分支。
                            if (q.options.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    q.options.forEach { option ->
                                        val selectedOptions = multiAnswers[q.id] ?: emptySet()
                                        FilterChip(
                                            selected = if (q.selectionType == "multi") {
                                                option in selectedOptions
                                            } else {
                                                answers[q.id] == option
                                            },
                                            onClick = {
                                                if (q.selectionType == "multi") {
                                                    multiAnswers[q.id] = if (option in selectedOptions) {
                                                        selectedOptions - option
                                                    } else {
                                                        selectedOptions + option
                                                    }
                                                } else {
                                                    answers[q.id] = option
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = option,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = answers[q.id] ?: "",
                                onValueChange = { answers[q.id] = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                            )
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            // v297：这里原来是「硬取 jsonObject/jsonPrimitive」，答案值一旦不是字符串
                            // （例如被存成数组），异常就在这条链上抛出且**不在 runCatching 范围内**，
                            // 整条消息渲染都会崩。改成逐段安全取值，取不到就回退显示原文。
                            val answersNode = (answerJson as? JsonObject)?.get("answers") as? JsonObject
                            val answerText = (answersNode?.get(q.id) as? JsonPrimitive)?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                // v300：跳过的题目答案是空串，直接显示会是一行莫名其妙的空白 ——
                                // 复用「跳过」这个词当占位（不新增文案）。
                                text = answerText.ifBlank { stringResource(R.string.chat_message_tool_ask_skip) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // v299：某题「本该有选项却没解析出来」时，把模型给的原始参数摊在卡片上。
                // 商汤渠道真机实遇「题干显示正常、选项一个都看不到」——先让用户当场能照着原文作答，
                // 同时把真实形状留成证据（同一份文本也写进了 App 内日志页，设置→日志可复制）。
                // ⚠️ 条件必须收窄（审查位抓到）：schema 允许纯文本题不带 options，
                // 只看「选项为空」会把所有渠道的正常自由文本提问卡都摊成一段 JSON。
                if (
                    isPending &&
                    questions.any { q ->
                        q.options.isEmpty() && (q.selectionType != "text" || q.hadOptionsField)
                    }
                ) {
                    Text(
                        text = arguments.toString().take(400),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Submit / 跳过（官方 2.5.1 版式 + v300 二改：跳过按钮）
                if (isPending && onToolAnswer != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // v300（二改）：跳过。
                        // 官方卡片只给「提交」，模型问的问题一旦不是你想要的（或选项丢了、
                        // 只剩自由文本框），人就卡在这里出不去——真机实遇。
                        // 跳过会把「用户未作答」如实回传给模型，让它自己决定继续，而不是空等。
                        TextButton(
                            onClick = {
                                val skipPayload = buildJsonObject {
                                    put("answers", buildJsonObject {
                                        if (questions.isEmpty()) {
                                            put("free_text", JsonPrimitive(""))
                                        } else {
                                            questions.forEach { q -> put(q.id, JsonPrimitive("")) }
                                        }
                                    })
                                    put(
                                        "note",
                                        JsonPrimitive(
                                            "用户跳过了本次询问，没有作答。请基于现有信息自行决定并继续，" +
                                                "不要重复问同样的问题；如果确实必须知道，请改用普通文字再问一次。"
                                        )
                                    )
                                }
                                onToolAnswer(tool.toolCallId, skipPayload.toString())
                            },
                        ) {
                            Text(stringResource(R.string.chat_message_tool_ask_skip))
                        }

                        FilledTonalButton(
                            onClick = {
                                // v297：没解析出任何题目时，把「卡片没能读出问题」这件事如实回传给模型。
                                // 否则模型只收到一个空 answers，会以为用户沉默，然后原地打转
                                // （这次就是主模型自己发错参数、拿回空答案却查不到原因）。
                                val answerPayload = if (questions.isEmpty()) {
                                    buildJsonObject {
                                        put("answers", buildJsonObject {
                                            put("free_text", JsonPrimitive(freeTextAnswer.trim()))
                                        })
                                        put(
                                            "note",
                                            JsonPrimitive(
                                                "ask_user 卡片没能从参数里解析出任何问题（参数形状不符合约定）。" +
                                                    "answers.free_text 里是用户的自由回答。" +
                                                    "需要提问请改用普通文本，或把 options 改成纯字符串列表后重发。"
                                            )
                                        )
                                    }
                                } else {
                                    buildJsonObject {
                                        put("answers", buildJsonObject {
                                            questions.forEach { q ->
                                                when (q.selectionType) {
                                                    // 官方 2.5.1：多选 = 已选选项 + 自定义文字（两者可同时存在）
                                                    "multi" -> put(q.id, JsonPrimitive(
                                                        (multiAnswers[q.id].orEmpty().toList() +
                                                            listOfNotNull(answers[q.id]?.takeIf { it.isNotBlank() }))
                                                            .joinToString(", ")
                                                    ))
                                                    else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                                }
                                            }
                                        })
                                    }
                                }
                                onToolAnswer(tool.toolCallId, answerPayload.toString())
                            },
                            // v297：空题目时不能再靠「空列表 all 恒真」放行，必须让用户真的打了字才能提交
                            // 官方 2.5.1 语义：多选「选了选项」或「打了字」都算答过
                            enabled = if (questions.isEmpty()) {
                                freeTextAnswer.isNotBlank()
                            } else {
                                questions.all { q ->
                                    when (q.selectionType) {
                                        "multi" -> !multiAnswers[q.id].isNullOrEmpty() || !answers[q.id].isNullOrBlank()
                                        else -> !answers[q.id].isNullOrBlank()
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.chat_message_tool_submit),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
