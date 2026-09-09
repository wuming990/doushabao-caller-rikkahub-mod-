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
        if (unreadable) {
            Log.w(TAG, "ask_user 未解析出任何问题（参数形状不符），已改用自由文本回答；原始参数片段: $arguments".take(400))
        }
        // v295：single/multi 缺 options 是「真机卡死」的上游现场（商汤渠道实遇），留证据
        if (questions.any { it.selectionType != "text" && it.options.isEmpty() }) {
            Log.w(TAG, "ask_user 选项缺失（single/multi 但 options 为空，已降级文本输入）；原始参数片段: $arguments".take(400))
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
                            when (q.selectionType) {
                                "single" -> {
                                    // Single select: chips only, no text input
                                    // v295：上游可能不生成 options（可选字段）——无选项时降级为
                                    // 自由文本输入，否则用户没有任何回答途径、提交键永远灰着（真机卡死）
                                    if (q.options.isEmpty()) {
                                        OutlinedTextField(
                                            value = answers[q.id] ?: "",
                                            onValueChange = { answers[q.id] = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            singleLine = false,
                                            minLines = 1,
                                            maxLines = 3,
                                        )
                                    }
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
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
                                }
                                "multi" -> {
                                    // Multi select: chips only, multiple can be selected
                                    // v295：同 single —— 无选项时降级为自由文本输入（答案走 answers）
                                    if (q.options.isEmpty()) {
                                        OutlinedTextField(
                                            value = answers[q.id] ?: "",
                                            onValueChange = { answers[q.id] = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            singleLine = false,
                                            minLines = 1,
                                            maxLines = 3,
                                        )
                                    }
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                FilterChip(
                                                    selected = selectedSet.contains(option),
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
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
                                }
                                else -> {
                                    // Text (default): optional option chips + free text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
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

                                    // Free text input
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                    )
                                }
                            }
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
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
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
                                                // v295：无选项时取文本框内容
                                                "multi" -> put(q.id, JsonPrimitive(
                                                    if (q.options.isEmpty()) answers[q.id] ?: ""
                                                    else multiAnswers[q.id]?.joinToString(", ") ?: ""
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
                        enabled = if (questions.isEmpty()) {
                            freeTextAnswer.isNotBlank()
                        } else {
                            questions.all { q ->
                                when (q.selectionType) {
                                    // v295：无选项时答案在文本框（answers）里
                                    "multi" -> if (q.options.isEmpty()) {
                                        !answers[q.id].isNullOrBlank()
                                    } else {
                                        !multiAnswers[q.id].isNullOrEmpty()
                                    }
                                    else -> !answers[q.id].isNullOrBlank()
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
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
