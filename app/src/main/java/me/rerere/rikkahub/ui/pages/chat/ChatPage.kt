package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.hooks.AttachmentPending
import me.rerere.rikkahub.ui.hooks.AttachmentPendingStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.RoundTableTaskGuard
import me.rerere.rikkahub.service.approxConversationTokens
import me.rerere.rikkahub.service.buildContextUsageInfo
import me.rerere.rikkahub.service.reportedConversationTokens
import me.rerere.rikkahub.service.resolveAutoCompressTriggerTokens
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.ChatFileBrowserSheet
import me.rerere.rikkahub.ui.components.ai.ChatImageTarget
import me.rerere.rikkahub.ui.components.ai.ChatImageTargetDialog
import me.rerere.rikkahub.data.db.dao.ConversationTokenStats
import me.rerere.rikkahub.ui.components.ai.ConversationTokenStatsDialog
import me.rerere.rikkahub.ui.components.ai.ConversationTokenStatsLine
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ChatPage(
    id: Uuid,
    text: String?,
    files: List<Uri>,
    nodeId: Uuid? = null,
    // v224：从圆桌「查看过程」跳进来的过程对话，顶栏要给一个明确的返回按钮，
    // 用户原话：「需要保证可以正常回到主对话」。
    showBack: Boolean = false,
) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    showBack = showBack,
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    showBack = showBack,
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    // v224：圆桌「查看过程」进来的过程对话，顶栏用返回箭头代替抽屉按钮
    showBack: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    // v2.5.0 合并：语音模式入口与队列状态（官方）——本层作用域内定义
    val startVoiceMode = rememberVoiceModeStarter(vm, setting)
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // v255：附件条的「添加 / 重试 / 移除」要在面板关闭后也能用，所以这一层也要拿到这两个依赖
    val filesManager: FilesManager = koinInject()
    val appScope: AppScope = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = setting.getCurrentAssistant()
    var showFilesSheet by remember { mutableStateOf(false) }
    // v213：圆桌座位状态；控制面板据此显示每个位置由谁负责、跑到哪一步
    val roundTableRun by vm.roundTableRun.collectAsStateWithLifecycle()
    val agentThreads by vm.agentThreads.collectAsStateWithLifecycle()
    // v209：圆桌确认框的待确认请求；非空时弹框。放在这一层，底部面板关掉也不会跟着消失。
    var roundTableRequest by remember { mutableStateOf<RoundTableConfirmRequest?>(null) }

    // 输入框上方常显的「当前上下文占用」。
    // 真实用量的读取很便宜，可以跟着对话实时更新；没有真实用量时才需要遍历全文估算，
    // 那条路径按消息节点数量做节流，避免流式输出过程中每收到一个字都把整段对话重算一遍。
    val contextUsage = if (setting.displaySetting.showContextUsage) {
        val reportedTokens = remember(conversation.messageNodes) {
            reportedConversationTokens(conversation)
        }
        val approxTokens = remember(conversation.messageNodes.size, reportedTokens == null) {
            if (reportedTokens == null) approxConversationTokens(conversation) else 0L
        }
        // 分母跟着「特殊模型」表走：当前模型单独设过就用它自己的值
        val triggerTokens = remember(
            assistant.autoCompressTriggerTokens,
            assistant.chatModelId,
            setting.chatModelId,
            setting.autoCompressModelOverrides,
        ) {
            resolveAutoCompressTriggerTokens(
                assistant = assistant,
                chatModelId = assistant.chatModelId ?: setting.chatModelId,
                overrides = setting.autoCompressModelOverrides,
            )
        }
        remember(reportedTokens, approxTokens, assistant.enableAutoCompress, triggerTokens) {
            buildContextUsageInfo(
                assistant = assistant,
                triggerTokens = triggerTokens,
                reportedTokens = reportedTokens,
                approxTokens = approxTokens,
            )
        }
    } else {
        null
    }

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    // v297：本对话 token 累计（顶栏小字，可在设置里关掉）
                    tokenStats = vm.conversationTokenStats.collectAsStateWithLifecycle().value,
                    showTokenStats = setting.displaySetting.showConversationTokenStats,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                    onBack = if (showBack) {
                        { navController.popBackStack() }
                    } else {
                        null
                    },
                )
            },
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                Column {
                    // v218：独立子代理活动面板（与圆桌无关）
                    // v224：子代理总开关关掉后完全不显示，不再占着输入框上方的位置、
                    // 也不会挡住圆桌面板（用户原话：「不要散落在外面影响我启用圆桌模式」）。
                    if (setting.enableAgentTools) {
                        // v284：助手级分闸。总闸开着时，仍由当前助手自己决定要不要子代理
                        // （用户原话：「我需要这个助手开子代理，另外一个助手关闭都做不到」）。
                        // 外层这个 if 是 v224 的门禁（有测试按原文钉住），所以分闸套在里面。
                        if (assistant.enableAgentTools) {
                            AgentActivityPanel(
                                threads = agentThreads,
                                onStop = { vm.stopAgent(it) },
                                onMerge = { vm.mergeAgentReport(it) },
                                onClose = { vm.closeAgent(it) },
                                onCloseFinished = { vm.closeFinishedAgents() },
                                onOpenThreadChat = { threadId ->
                                    navController.navigate(Screen.AgentThreadChat(threadId, conversation.id.toString()))
                                },
                            )
                        }
                    }
                    // v213：圆桌进行中显示座位控制面板（停止/换模型/重试/跳过 + 缺席检查点）
                    RoundTableSeatPanel(
                        state = roundTableRun,
                        providers = setting.providers,
                        onCommand = { seatId, command ->
                            vm.controlRoundTableSeat(seatId, command)
                        },
                        onToggleInclude = { seatId, include ->
                            vm.setRoundTableSeatIncluded(seatId, include)
                        },
                        onGapDecision = { decision ->
                            vm.resolveRoundTableGap(decision)
                        },
                        // v224：直接进这个位置自己的对话看过程（原生聊天页，返回即回主对话）
                        onOpenSeatChat = { seatConversationId ->
                            navController.navigate(
                                Screen.Chat(
                                    id = seatConversationId,
                                    showBack = true,
                                )
                            )
                        },
                    )
                    ChatInput(
                    onStartVoiceMode = if (
                        setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                        voiceState.phase == VoicePhase.Off
                    ) {
                        {
                            showFilesSheet = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            startVoiceMode()
                        }
                    } else null,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceSession::stop,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    contextUsage = contextUsage,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onAddMoreAttachment = {
                        showFilesSheet = true
                    },
                    onRetryPendingAttachment = { uriString ->
                        retryAttachCopy(
                            uriString = uriString,
                            scope = appScope,
                            filesManager = filesManager,
                            inputState = inputState,
                            toaster = toaster,
                        )
                    },
                    onRemovePendingAttachment = { uriString ->
                        inputState.removePending(uriString)
                    },
                    onUpdateSearchMode = { mode ->
                        val current = setting.getCurrentAssistant()
                        val model = setting.getCurrentChatModel()
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == current.id) {
                                        assistant.copy(enableWebSearch = mode == SearchMode.LOCAL)
                                    } else {
                                        assistant
                                    }
                                },
                                providers = if (model == null) {
                                    setting.providers
                                } else {
                                    setting.providers.map { provider ->
                                        provider.editModel(
                                            model.copy(
                                                tools = if (mode == SearchMode.BUILT_IN) {
                                                    model.tools + BuiltInTools.Search
                                                } else {
                                                    model.tools - BuiltInTools.Search
                                                }
                                            )
                                        )
                                    }
                                },
                            )
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        // v255：附件还在处理中 / 有失败附件时不让发送
                        if (inputState.hasPendingProcessing()) {
                            toaster.show("附件还在准备中，请稍候", type = ToastType.Warning)
                            return@ChatInput
                        }
                        if (inputState.hasFailedPending()) {
                            toaster.show("有附件处理失败，请点红色附件重试或移除", type = ToastType.Warning)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            val contents = inputState.getContents()
                            if (vm.shouldAutoCompressBeforeSend()) {
                                scope.launch {
                                    toaster.show(
                                        context.getString(R.string.chat_page_auto_compress_branching),
                                        type = ToastType.Info,
                                    )
                                    runCatching {
                                        val fork = vm.autoCompressForkForSend()
                                        navigateToChatPage(navController, chatId = fork.id)
                                        vm.sendMessageToConversation(fork.id, contents)
                                    }.onFailure {
                                        toaster.show(
                                            context.getString(R.string.chat_page_auto_compress_failed_fallback),
                                            type = ToastType.Warning,
                                        )
                                        vm.handleMessageSend(contents)
                                    }
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            } else {
                                vm.handleMessageSend(contents)
                                scope.launch {
                                    delay(100.milliseconds)
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
                onRequestRoundTable = { autoSummarize ->
                    val contents = inputState.getContents()
                    roundTableRequest = RoundTableConfirmRequest(
                        autoSummarize = autoSummarize,
                        text = contents.filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text }
                            .trim(),
                        attachmentCount = contents.count { it !is UIMessagePart.Text },
                    )
                },
            )
        }

        // v209：圆桌确认框。先让用户看清并可以修改本轮任务，确认后才真正开始花钱。
        roundTableRequest?.let { request ->
            var taskText by remember(request) { mutableStateOf(request.text) }
            val roundTable = setting.roundTableSetting
            val memberCount = roundTable.memberModelIds.distinct()
                .count { setting.findModelById(it) != null }
            val estimatedCalls = memberCount +
                (if (roundTable.enableContractStage) 1 else 0) +
                (if (roundTable.enableMainDraft) 1 else 0) +
                (if (request.autoSummarize && roundTable.enableRebuttalStage) 1 else 0) +
                (if (request.autoSummarize) 1 else 0)
            val hasHistory = conversation.currentMessages.isNotEmpty()
            // v229：预设内容不算「用户说过的话」。
            // 旧写法 canStart = taskText.isNotBlank() || hasHistory 会让配了预设内容的助手
            // 在新建对话时「空任务也能开跑」，圆桌整轮跑完没有一个模型拿到用户的要求，
            // 而输入框里的字还被清空了（真机事故）。
            val canStart = RoundTableTaskGuard.canStart(
                taskText = taskText,
                hasAttachment = request.attachmentCount > 0,
                userTexts = conversation.currentMessages
                    .filter { it.role == MessageRole.USER }
                    .map { it.toText() },
                presetTexts = assistant.presetMessages.map { it.toText() },
            )
            AlertDialog(
                onDismissRequest = { roundTableRequest = null },
                title = { Text(stringResource(R.string.round_table_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                R.string.round_table_confirm_desc,
                                estimatedCalls,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = taskText,
                            onValueChange = { taskText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                            placeholder = {
                                Text(stringResource(R.string.round_table_confirm_placeholder))
                            },
                        )
                        if (request.attachmentCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.round_table_confirm_attachments,
                                    request.attachmentCount,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (taskText.isBlank() && hasHistory) {
                            Text(
                                text = stringResource(R.string.round_table_confirm_empty_with_history),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = canStart,
                        onClick = {
                            val contents = inputState.getContents()
                            val attachments = contents.filterNot { it is UIMessagePart.Text }
                            val task = taskText.trim()
                            val parts = buildList {
                                if (task.isNotEmpty()) add(UIMessagePart.Text(task))
                                addAll(attachments)
                            }
                            vm.runRoundTable(parts, request.autoSummarize)
                            // v229：只有本轮真的带上了任务或附件才清空输入框。
                            // 旧行为是无条件清空，于是「任务为空却被放行」时，
                            // 用户打的字既没存进对话、也被输入框清掉，等于凭空消失。
                            if (parts.isNotEmpty()) {
                                inputState.clearInput()
                            }
                            roundTableRequest = null
                            toaster.show(
                                context.getString(
                                    R.string.round_table_toast_started,
                                    memberCount,
                                ),
                                type = ToastType.Info,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.round_table_confirm_start))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { roundTableRequest = null }) {
                        Text(stringResource(R.string.chat_page_cancel))
                    }
                },
            )
        }
    }
}

/** v209：圆桌确认框的待确认请求。attachmentCount 只用于提示，附件本身在确认时重新从输入框取 */
private data class RoundTableConfirmRequest(
    val autoSummarize: Boolean,
    val text: String,
    val attachmentCount: Int,
)

/**
 * v255：附件统一处理（非 Composable，聊天页内层面板与外层输入框共用同一套逻辑）。
 *
 * 流程：数量/大小校验 → 标记「处理中」→ 复制到本地上传目录（IO 线程）→
 * 成功进输入框（图片可另存工作区）/ 失败标记「失败，点一下重试」。
 *
 * 刻意要求传入 [scope] 而不是自己 rememberCoroutineScope：选完文件后底部面板会立刻关闭，
 * 面板作用域会随之取消，复制会中途断掉。调用方应传应用级 AppScope。
 */
private fun startAttachCopy(
    uris: List<Uri>,
    scope: CoroutineScope,
    filesManager: FilesManager,
    inputState: ChatInputState,
    toaster: ToasterState,
    onLocalImageReady: (List<Uri>) -> Unit = {},
) {
    if (uris.isEmpty()) return
    val existingCount = inputState.messageContent.size + inputState.pendingAttachments.size
    if (existingCount + uris.size > FilesManager.MAX_ATTACH_COUNT) {
        toaster.show(
            "附件最多 ${FilesManager.MAX_ATTACH_COUNT} 个，请先减少数量再试",
            type = ToastType.Warning,
        )
        return
    }
    uris.forEach { uri ->
        val name = filesManager.getFileNameFromUri(uri) ?: "附件"
        val mime = filesManager.getFileMimeType(uri)
        val kind = filesManager.classifyAttachment(mime)
        val sizeBytes = filesManager.getUriSizeBytes(uri)
        val limit = if (kind == FilesManager.AttachmentKind.IMAGE) {
            FilesManager.MAX_ATTACH_IMAGE_BYTES
        } else {
            FilesManager.MAX_ATTACH_DOC_BYTES
        }
        // 取不到大小（返回 null 或 -1）时不拦，交给复制本身去失败
        if (sizeBytes != null && sizeBytes > 0 && sizeBytes > limit) {
            toaster.show(
                "$name 超过 ${limit / 1024 / 1024}MB，已跳过",
                type = ToastType.Warning,
            )
            return@forEach
        }
        val key = uri.toString()
        inputState.updatePending(
            key,
            AttachmentPending(displayName = name, status = AttachmentPendingStatus.PROCESSING),
        )
        scope.launch {
            runCatching { filesManager.copyChatFile(uri) }
                .onSuccess { localUri ->
                    // 用户在复制途中点了移除 → 不再塞进输入框，避免出现删不掉的孤儿附件
                    if (!inputState.pendingAttachments.containsKey(key)) return@onSuccess
                    inputState.removePending(key)
                    val displayName = filesManager.getFileNameFromUri(localUri) ?: name
                    when (kind) {
                        FilesManager.AttachmentKind.IMAGE -> {
                            inputState.addImages(listOf(localUri))
                            onLocalImageReady(listOf(localUri))
                        }

                        FilesManager.AttachmentKind.VIDEO -> inputState.addVideos(listOf(localUri))

                        FilesManager.AttachmentKind.AUDIO -> inputState.addAudios(listOf(localUri))

                        FilesManager.AttachmentKind.DOCUMENT -> inputState.addFiles(
                            listOf(
                                UIMessagePart.Document(
                                    url = localUri.toString(),
                                    fileName = displayName,
                                    mime = mime ?: "text/*",
                                )
                            )
                        )
                    }
                }
                .onFailure { e ->
                    inputState.updatePending(
                        key,
                        AttachmentPending(
                            displayName = name,
                            status = AttachmentPendingStatus.FAILED,
                            error = e.message ?: "处理失败，点击重试",
                        ),
                    )
                }
        }
    }
}

/** v255：重试一条失败的附件（附件条上点红色 chip 触发） */
private fun retryAttachCopy(
    uriString: String,
    scope: CoroutineScope,
    filesManager: FilesManager,
    inputState: ChatInputState,
    toaster: ToasterState,
) {
    if (!inputState.pendingAttachments.containsKey(uriString)) return
    inputState.removePending(uriString)
    val uri = runCatching { uriString.toUri() }.getOrNull() ?: return
    startAttachCopy(
        uris = listOf(uri),
        scope = scope,
        filesManager = filesManager,
        inputState = inputState,
        toaster = toaster,
    )
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onDismiss: () -> Unit,
    onRequestRoundTable: (autoSummarize: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    // v2.5.0 合并：语音模式入口与队列状态（官方）——本层作用域内定义
    val startVoiceMode = rememberVoiceModeStarter(vm, setting)
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val filesManager: FilesManager = koinInject()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val appScope: AppScope = koinInject()
    val workspaces by workspaceRepository.listFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showFileBrowser by remember { mutableStateOf(false) }
    var showImageTargetDialog by remember { mutableStateOf(false) }
    var imageTarget by remember { mutableStateOf<ChatImageTarget>(ChatImageTarget.AiOnly) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        showFileBrowser = false
        showImageTargetDialog = false
        onDismiss()
    }

    /**
     * 圆桌入口（v208 从输入框快捷栏移到这里；v209 改为先让上层弹确认框）。
     * 这里只把请求交出去，不启动、不清空输入框、不花钱。
     */
    fun startRoundTable(autoSummarize: Boolean) {
        onRequestRoundTable(autoSummarize)
    }

    /**
     * 选择“保存到工作区”时，把已经落到本地上传目录的图片再复制一份到工作区。
     * 使用 AppScope，避免底部弹窗关闭后协程被取消导致复制中断。
     */
    fun saveImagesToWorkspaceIfNeeded(localUris: List<Uri>) {
        val target = imageTarget as? ChatImageTarget.Workspace ?: return
        if (localUris.isEmpty()) return
        appScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    var saved = 0
                    localUris.forEachIndexed { index, uri ->
                        val file = runCatching { uri.toFile() }.getOrNull() ?: return@forEachIndexed
                        if (!file.isFile) return@forEachIndexed
                        file.inputStream().use { input ->
                            workspaceRepository.importFile(
                                id = target.workspaceId,
                                area = WorkspaceStorageArea.FILES,
                                destinationPath = ChatImageWorkspaceNaming.DIRECTORY,
                                fileName = ChatImageWorkspaceNaming.fileName(file.name, index),
                                inputStream = input,
                            )
                        }
                        saved++
                    }
                    saved
                }
            }.onSuccess { saved ->
                if (saved > 0) {
                    toaster.show(
                        context.getString(
                            R.string.chat_image_saved_to_workspace,
                            saved,
                            target.workspaceName,
                        ),
                        type = ToastType.Success,
                    )
                } else {
                    toaster.show(
                        context.getString(R.string.chat_image_saved_to_workspace_failed),
                        type = ToastType.Error,
                    )
                }
            }.onFailure {
                Log.e("ChatFilesPickerSheet", "Failed to copy picked images into workspace", it)
                toaster.show(
                    context.getString(R.string.chat_image_saved_to_workspace_failed),
                    type = ToastType.Error,
                )
            }
        }
    }

    /**
     * v255：统一附件入口 —— 校验（数量 / 类型 / 大小）→ 标记「处理中」→ 异步复制到本地 →
     * 成功进输入框（并按需保存到工作区）/ 失败标记「失败，可点击重试」。
     */
    fun attachSources(uris: List<Uri>, kind: FilesManager.AttachmentKind) {
        if (uris.isEmpty()) return
        // v255：复制走应用级作用域 —— 面板马上会关闭，用面板自己的作用域会把复制掐断
        startAttachCopy(
            uris = uris,
            scope = appScope,
            filesManager = filesManager,
            inputState = inputState,
            toaster = toaster,
            onLocalImageReady = { localUris -> saveImagesToWorkspaceIfNeeded(localUris) },
        )
        dismissAll()
    }

    // v255：Kotlin 局部函数必须先声明后使用 —— attachPickedImages 依赖 attachSources，
    // 所以放在它后面
    fun attachPickedImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        attachSources(uris, FilesManager.AttachmentKind.IMAGE)
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            attachPickedImages(listOf(croppedUri))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    attachPickedImages(selectedUris)
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    attachPickedImages(selectedUris)
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                attachSources(selectedUris, FilesManager.AttachmentKind.VIDEO)
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                attachSources(selectedUris, FilesManager.AttachmentKind.AUDIO)
            }
        }

    fun attachDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        attachSources(uris, FilesManager.AttachmentKind.DOCUMENT)
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, modelSource, customPrompt ->
                vm.handleCompressContext(additionalPrompt, modelSource, customPrompt)
            },
            // v268：经典压缩（官方旧版流程）
            onCompressContextClassic = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContextClassic(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateSettings = { vm.updateSettings(it) },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = {
                imageTarget = ChatImageTarget.AiOnly
                showImageTargetDialog = true
            },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { showFileBrowser = true },
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    startVoiceMode()
                }
            } else null,
            onRunRoundTable = {
                startRoundTable(autoSummarize = true)
            },
            onRunRoundTableProposalsOnly = {
                startRoundTable(autoSummarize = false)
            },
            onSummarizeRoundTable = {
                val nodeId = vm.findLatestRoundTableNodeId()
                if (nodeId == null) {
                    toaster.show(
                        context.getString(R.string.round_table_error_no_proposals),
                        type = ToastType.Error,
                    )
                } else {
                    vm.summarizeRoundTableNode(nodeId)
                }
            },
        )
    }

    if (showImageTargetDialog) {
        ChatImageTargetDialog(
            workspaces = workspaces,
            onSelect = { target ->
                imageTarget = target
                showImageTargetDialog = false
                imagePickerLauncher.launch("image/*")
            },
            onDismiss = { showImageTargetDialog = false },
        )
    }

    if (showFileBrowser) {
        ChatFileBrowserSheet(
            onDismiss = { showFileBrowser = false },
            onFilesSelected = ::attachDocuments,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    // v297：本对话 token 累计（点开展示口径说明；开关关闭时整行不渲染）
    tokenStats: ConversationTokenStats = ConversationTokenStats(),
    showTokenStats: Boolean = false,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    // v224：非空时顶栏左侧显示返回箭头（圆桌过程对话用）
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    // v297：点顶栏那行 token 小字弹出的明细窗
    var showTokenStatsDialog by remember { mutableStateOf(false) }
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, stringResource(R.string.round_table_seat_chat_back))
                }
            } else if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                    // v297：本对话 token 累计（开关关 / 没有用量数据时都不渲染，不占位置）
                    if (showTokenStats) {
                        ConversationTokenStatsLine(
                            stats = tokenStats,
                            onClick = { showTokenStatsDialog = true },
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    // v297：本对话 token 明细（含统计口径说明：不含后台调用与子代理）
    if (showTokenStatsDialog) {
        ConversationTokenStatsDialog(
            stats = tokenStats,
            onDismiss = { showTokenStatsDialog = false },
        )
    }
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
