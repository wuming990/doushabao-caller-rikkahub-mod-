package me.rerere.rikkahub.ui.pages.setting.components

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.webkit.CookieManager
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.ui.toast.ToastType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.providers.claude.ClaudeCompatMemory
import me.rerere.ai.provider.providers.claude.ClaudeThinkingStyle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.UsageWindow
import me.rerere.ai.provider.supportsQuota
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.account.AccountLoginStatus
import me.rerere.rikkahub.data.ai.account.ChatGPTAuth
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.rikkahub.data.ai.account.ProviderAccountManager
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.gemini.GeminiWebLogin
import me.rerere.rikkahub.data.gemini.normalizeGeminiWebCookie
import me.rerere.rikkahub.ui.activity.GeminiWebLoginActivity
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DashboardSpeed02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Login03
import me.rerere.hugeicons.stroke.Logout03
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.components.ai.longLabel
import me.rerere.rikkahub.ui.components.ai.rememberProviderQuota
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import me.rerere.rikkahub.utils.toMessageTimeString
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlin.time.toJavaInstant

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    allowTypeSwitch: Boolean = false,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
    ) {
        provider.description()

        CardGroup {
            // 提供商创建后类型就定死了，只有模型的提供商覆盖可以换类型
            if (allowTypeSwitch) {
                formItem {
                    ConnectedChoiceButtons(
                        options = ProviderSetting.Types,
                        selected = provider::class,
                        onSelect = { onEdit(provider.convertTo(it)) },
                    ) { type, selected ->
                        ConnectedChoiceLabel(text = type.simpleName ?: "", selected = selected)
                    }
                }
            }
            switchItem(
                checked = provider.enabled,
                onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_enable)) },
            )
        }

        when (provider) {
            is ProviderSetting.OpenAI -> ProviderConfigureOpenAI(provider, onEdit)
            is ProviderSetting.Google -> ProviderConfigureGoogle(provider, onEdit)
            is ProviderSetting.Claude -> ProviderConfigureClaude(provider, onEdit)
            is ProviderSetting.ChatGPT -> ProviderConfigureAccount(
                provider = provider,
                onEdit = onEdit,
                serviceName = "ChatGPT",
                plan = provider.credential?.plan?.let { "ChatGPT ${ChatGPTAuth.planDisplayName(it)}" },
                disclaimer = stringResource(R.string.setting_provider_page_chatgpt_disclaimer),
            )

            // v323：二改的三套账号通道沿用各自的登录/多账号界面（不能换成官方的单凭据卡片，
            // 否则多账号轮询、Grok 额度与 Gemini 项目选择都会消失）
            is ProviderSetting.Grok -> GrokProviderConfigure(provider, onEdit)
            is ProviderSetting.Codex -> CodexProviderConfigure(provider, onEdit)
            is ProviderSetting.GeminiOAuth -> GeminiProviderConfigure(provider, onEdit)
            is ProviderSetting.GeminiWeb -> GeminiWebProviderConfigure(provider, onEdit)
        }
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) return this

    // 账号凭据不能带到可以自填地址的类型上，从账号类转过来时 key、地址和额度查询都用目标类型的默认值
    val source = this as? ProviderSetting.ApiKey
    val apiKey = source?.apiKey.orEmpty()
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = source?.baseUrl?.convertToTargetBaseUrl(targetDefaultBaseUrl) ?: targetDefaultBaseUrl

    val converted: ProviderSetting.ApiKey = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        else -> error("Unsupported provider type: $type")
    }
    // 沿用用户配的额度查询
    return source?.let { converted.withQuotaQuery(it.quotaQuery) } ?: converted
}

internal fun ProviderSetting.ApiKey.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider is ProviderSetting.ApiKey && defaultProvider::class == this::class) {
        return defaultProvider.baseUrl
    }
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
    }
}

internal fun ProviderSetting.ApiKey.isUsingDefaultBaseUrl(): Boolean = baseUrl == defaultBaseUrlForReset()

// 地址不是默认值时，输入框右侧出现的恢复默认按钮
private fun resetBaseUrlButton(
    provider: ProviderSetting.ApiKey,
    onReset: () -> Unit,
): (@Composable () -> Unit)? {
    if (provider.isUsingDefaultBaseUrl()) return null
    return {
        IconButton(onClick = onReset) {
            Icon(
                imageVector = HugeIcons.Refresh03,
                contentDescription = stringResource(R.string.setting_model_page_reset_to_default),
            )
        }
    }
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder().encodedPath(convertedPath).build().toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST
)

// 标题在上、输入框在下的表单项；secret 为 true 时默认遮住内容，右侧是显示/隐藏按钮
private fun CardGroupScope.textFieldItem(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    secret: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    monospace: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) = formItem(
    headlineContent = { Text(stringResource(label)) },
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono)
        } else LocalTextStyle.current,
        supportingText = supportingText,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            }
        } else trailingIcon,
    )
}

private fun CardGroupScope.nameItem(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) = textFieldItem(
    label = R.string.setting_provider_page_name,
    value = provider.name,
    onValueChange = { onEdit(provider.copyProvider(name = it)) },
)

private fun CardGroupScope.apiKeyItem(
    apiKey: String,
    onValueChange: (String) -> Unit,
) = textFieldItem(
    label = R.string.setting_provider_page_api_key,
    value = apiKey,
    onValueChange = { onValueChange(it.trim()) },
    secret = true,
    maxLines = 3,
)

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val responseAPIWarning = stringResource(R.string.setting_provider_page_response_api_warning)

    CardGroup {
        nameItem(provider, onEdit)
        apiKeyItem(provider.apiKey) { onEdit(provider.copy(apiKey = it)) }
        textFieldItem(
            label = R.string.setting_provider_page_api_base_url,
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
            trailingIcon = resetBaseUrlButton(provider) {
                onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
            },
        )
        textFieldItem(
            label = R.string.setting_provider_page_api_path,
            value = if (provider.useResponseApi) provider.responsesPath else provider.chatCompletionsPath,
            onValueChange = {
                onEdit(
                    if (provider.useResponseApi) {
                        provider.copy(responsesPath = it.trim())
                    } else {
                        provider.copy(chatCompletionsPath = it.trim())
                    }
                )
            },
            enabled = !provider.builtIn,
        )
    }

    CardGroup {
        switchItem(
            checked = provider.useResponseApi,
            onCheckedChange = {
                onEdit(provider.copy(useResponseApi = it))
                if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(message = responseAPIWarning, type = ToastType.Warning)
                }
            },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_response_api)) },
        )
        switchItem(
            checked = provider.includeHistoryReasoning,
            onCheckedChange = { onEdit(provider.copy(includeHistoryReasoning = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_include_history_reasoning)) },
        )
    }
}

// 账号类提供商：登录状态、用量和说明
@Composable
private fun ProviderConfigureAccount(
    provider: ProviderSetting.Account,
    onEdit: (provider: ProviderSetting) -> Unit,
    serviceName: String,
    plan: String?,
    disclaimer: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountManager = koinInject<ProviderAccountManager>()
    val loginStatus by accountManager.loginStatus.collectAsStateWithLifecycle()
    val status = loginStatus[provider.id]
    val credential = provider.credential

    CardGroup {
        nameItem(provider, onEdit)
    }

    CardGroup(title = { Text(stringResource(R.string.setting_provider_page_account)) }) {
        if (credential != null) {
            item(
                leadingContent = { CardGroupIcon(HugeIcons.UserCircle) },
                supportingContent = plan?.let { { Text(it) } },
                headlineContent = { Text(credential.email ?: stringResource(R.string.setting_provider_page_signed_in)) },
            )
            item(
                onClick = { scope.launch { accountManager.logout(provider.id) } },
                leadingContent = {
                    CardGroupIcon(
                        icon = HugeIcons.Logout03,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_sign_out)) },
            )
        } else if (status is AccountLoginStatus.DeviceCode) {
            item(
                onClick = { CustomTabsOAuthAuthorizationLauncher.launch(context, status.verificationUrl) },
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                overlineContent = { Text(stringResource(R.string.setting_provider_page_device_code_hint)) },
                supportingContent = { Text(stringResource(R.string.setting_provider_page_device_code_reopen)) },
                headlineContent = {
                    Text(
                        text = status.userCode,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        fontFamily = JetbrainsMono,
                    )
                },
            )
            item(
                onClick = { accountManager.cancelLogin(provider.id) },
                leadingContent = { CardGroupIcon(HugeIcons.Cancel01) },
                headlineContent = { Text(stringResource(R.string.cancel)) },
            )
        } else if (status == AccountLoginStatus.Authorizing) {
            item(
                onClick = { accountManager.cancelLogin(provider.id) },
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                supportingContent = { Text(stringResource(R.string.setting_provider_page_waiting_authorization_desc)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_waiting_authorization)) },
            )
        } else {
            item(
                onClick = { accountManager.startLogin(provider.id, context) },
                leadingContent = { CardGroupIcon(HugeIcons.Login03) },
                supportingContent = (status as? AccountLoginStatus.Failed)?.let { failed ->
                    { Text(failed.message, color = MaterialTheme.colorScheme.error) }
                },
                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_sign_in_with, serviceName)) },
            )
        }
    }

    if (provider.supportsQuota) {
        AccountUsage(provider)
    }

    Text(
        text = disclaimer,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// 订阅用量：每个窗口一条进度和重置时间
@Composable
private fun AccountUsage(provider: ProviderSetting.Account) {
    val result = rememberProviderQuota(provider)
    val limits = result?.getOrNull() as? ProviderQuota.UsageLimits
    CardGroup(title = { Text(stringResource(R.string.setting_provider_page_usage)) }) {
        when {
            result == null -> item(
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_loading)) },
            )

            limits == null -> item(
                leadingContent = { CardGroupIcon(HugeIcons.DashboardSpeed02) },
                supportingContent = {
                    Text(
                        text = result.exceptionOrNull()?.message.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_failed)) },
            )

            limits.windows.isEmpty() -> item(
                leadingContent = { CardGroupIcon(HugeIcons.DashboardSpeed02) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_none)) },
            )

            else -> limits.windows.forEach { window ->
                val reached = limits.limitReached || window.usedPercent >= 100
                formItem(
                    headlineContent = { Text(window.longLabel()) },
                    supportingContent = window.resetsAt?.let { resetsAt ->
                        {
                            val time = LocalDateTime.ofInstant(resetsAt.toJavaInstant(), ZoneId.systemDefault())
                            Text(stringResource(R.string.setting_provider_page_usage_resets, time.toMessageTimeString()))
                        }
                    },
                    trailingContent = {
                        Text(
                            text = "${window.usedPercent.roundToInt()}%",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = if (reached) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                ) {
                    LinearWavyProgressIndicator(
                        progress = { (window.usedPercent / 100).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (reached) MaterialTheme.colorScheme.error else ProgressIndicatorDefaults.linearColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    val context = LocalContext.current
    // 学到的形态是「软件自动改过的东西」，必须看得见、也必须有出口 ——
    // 里面可能包含「已关掉提示词缓存」，那对用户是静默涨价。
    var learnedRefresh by remember { mutableIntStateOf(0) }
    // 必须在这里算：CardGroup 的内容 lambda 是「收集条目」的构建器、不是 @Composable，
    // 把 remember 写进去编译器直接拒绝（第一版就是这么编不过的）。
    val learned = remember(provider.id, learnedRefresh) {
        ClaudeCompatMemory.of(context).entriesForProvider(provider.id.toString())
    }
    // 只撞过底、一次都没成功过时 entriesForProvider 是空的（它只列「学到的组合」），
    // 而那种情况下软件同样在背着用户从最退化档起步。出口必须一起出现，
    // 否则唯一的清除入口藏着，用户只能靠重启 App 解除。
    val exhaustedHere = remember(provider.id, learnedRefresh) {
        ClaudeCompatMemory.of(context).hasExhaustedForProvider(provider.id.toString())
    }
    CardGroup {
        nameItem(provider, onEdit)
        apiKeyItem(provider.apiKey) { onEdit(provider.copy(apiKey = it)) }
        textFieldItem(
            label = R.string.setting_provider_page_api_base_url,
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
            trailingIcon = resetBaseUrlButton(provider) {
                onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
            },
        )
    }

    CardGroup {
        switchItem(
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_caching)) },
        )
        if (provider.promptCaching) {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl)) },
            ) {
                ConnectedChoiceButtons(
                    options = ClaudePromptCacheTtl.entries,
                    selected = provider.promptCacheTtl,
                    onSelect = { onEdit(provider.copy(promptCacheTtl = it)) },
                ) { ttl, selected ->
                    ConnectedChoiceLabel(
                        text = when (ttl) {
                            ClaudePromptCacheTtl.FIVE_MINUTES -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_5m)
                            ClaudePromptCacheTtl.ONE_HOUR -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_1h)
                        },
                        selected = selected,
                    )
                }
            }
        }
        // v1012：Claude 思考参数格式。默认「自动」= 按型号能力选格式 + 撞 400 自动降档；
        // 其余三项是手动逃生口（选了手动就完全绕开降档阶梯，见 ClaudeThinkingCompat）。
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_thinking_compat)) },
            supportingContent = {
                Text(stringResource(R.string.setting_provider_page_claude_thinking_compat_support))
            },
        ) {
            ConnectedChoiceButtons(
                options = ClaudeThinkingStyle.entries,
                selected = provider.thinkingCompat,
                onSelect = { onEdit(provider.copy(thinkingCompat = it)) },
            ) { style, selected ->
                ConnectedChoiceLabel(
                    text = when (style) {
                        ClaudeThinkingStyle.AUTO -> stringResource(R.string.setting_provider_page_claude_thinking_compat_auto)
                        ClaudeThinkingStyle.ADAPTIVE -> stringResource(R.string.setting_provider_page_claude_thinking_compat_adaptive)
                        ClaudeThinkingStyle.LEGACY -> stringResource(R.string.setting_provider_page_claude_thinking_compat_legacy)
                        ClaudeThinkingStyle.OMIT -> stringResource(R.string.setting_provider_page_claude_thinking_compat_omit)
                    },
                    selected = selected,
                )
            }
        }
        // v1013：显示并允许清除「软件自己学到的请求形态」。
        // 记忆写在进程内共享实例里（与 provider 用的是同一份，否则清了个空对象），
        // 见 ClaudeCompatMemory.of。
        if (learned.isNotEmpty() || exhaustedHere) {
            learned.forEach { (modelId, label) ->
                item(
                    headlineContent = { Text(modelId) },
                    supportingContent = {
                        Text(stringResource(R.string.setting_provider_page_claude_learned_form, label))
                    },
                )
            }
            item(
                onClick = {
                    // 清掉之后立刻重读一遍，界面当场回到「没有学到任何东西」的样子。
                    // 只清内存不够：落盘的记录会跨重启继续生效。
                    ClaudeCompatMemory.of(context).forgetProvider(provider.id.toString())
                    learnedRefresh++
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_learned_reset)) },
                supportingContent = {
                    Text(stringResource(R.string.setting_provider_page_claude_learned_reset_support))
                },
            )
        }
    }
}

@Composable
private fun ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val json = Json.parseToJsonElement(content).jsonObject
            onEdit(
                provider.copy(
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }
    val useServiceAccount = provider.vertexAI && provider.useServiceAccount

    CardGroup {
        nameItem(provider, onEdit)
        if (!useServiceAccount) {
            apiKeyItem(provider.apiKey) { onEdit(provider.copy(apiKey = it)) }
        }
        if (!provider.vertexAI) {
            textFieldItem(
                label = R.string.setting_provider_page_api_base_url,
                value = provider.baseUrl,
                onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                isError = provider.baseUrl.isNotBlank() && (
                    !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
                    ),
                supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
                    { Text("The base URL usually ends with `/v1beta`") }
                } else null,
                trailingIcon = resetBaseUrlButton(provider) {
                    onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
                },
            )
        }
    }

    CardGroup {
        switchItem(
            checked = provider.vertexAI,
            onCheckedChange = { onEdit(provider.copy(vertexAI = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_vertex_ai)) },
        )
        if (provider.vertexAI) {
            switchItem(
                checked = provider.useServiceAccount,
                onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_use_service_account)) },
            )
        } else {
            switchItem(
                checked = provider.useInteractionsApi,
                onCheckedChange = { onEdit(provider.copy(useInteractionsApi = it)) },
                headlineContent = { Text("Interactions API (Beta)") },
            )
        }
    }

    if (useServiceAccount) {
        CardGroup {
            item(
                onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_import_service_account_json)) },
                trailingContent = { Icon(HugeIcons.FileImport, contentDescription = null) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_service_account_email,
                value = provider.serviceAccountEmail,
                onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_private_key,
                value = provider.privateKey,
                onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
                secret = true,
                minLines = 3,
                maxLines = 6,
                monospace = true,
            )
            textFieldItem(
                label = R.string.setting_provider_page_location,
                value = provider.location,
                onValueChange = { onEdit(provider.copy(location = it.trim())) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_project_id,
                value = provider.projectId,
                onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
            )
        }
    }
}

/**
 * v323：Gemini 网页版配置。
 *
 * 只收 Cookie：这是复用浏览器会话，不是 API Key。模型列表由 Provider.listModels 固定给出
 * （gemini-3.1-pro / 3.8-flash / 3.5-flash-lite），在供应商详情的模型区刷新即可。
 */
@Composable
private fun GeminiWebProviderConfigure(
    provider: ProviderSetting.GeminiWeb,
    onEdit: (provider: ProviderSetting) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    // v1016：一键登录回传的 Cookie，**连同登录时用的那个浏览器标识**一起写进本供应商。
    // 标识不同源会被 Google 判定会话被搬到别的浏览器上，表现就是"Cookie 明明是对的却一发就 403"。
    val webLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // v1016：这里**不能**先按 resultCode 提前 return —— 用户带着"为什么没成功"离开时
        // resultCode 是 CANCELED，提前 return 会把那句原因整条丢掉（审查位抓到的死代码）。
        // 只有登录成功那条路才会写 EXTRA_COOKIE，所以直接按"有没有 Cookie"分岔就够。
        val data = result.data
        val cookie = data?.getStringExtra(GeminiWebLogin.EXTRA_COOKIE)
        val userAgent = data?.getStringExtra(GeminiWebLogin.EXTRA_USER_AGENT)
        val failure = data?.getStringExtra(GeminiWebLogin.EXTRA_ERROR)
        if (cookie.isNullOrBlank() || result.resultCode != Activity.RESULT_OK) {
            // 两个都空 = 用户自己关掉了登录页，不打扰；有原因就说原因。
            if (!failure.isNullOrBlank()) {
                toaster.show(failure, type = ToastType.Error)
            }
        } else {
            onEdit(
                provider.copy(
                    cookie = cookie,
                    userAgent = userAgent?.trim()?.ifBlank { null } ?: provider.userAgent,
                ),
            )
            toaster.show(
                "已登录，抓到：" + GeminiWebLogin.presentKeys(cookie).joinToString("、"),
                type = ToastType.Success,
            )
        }
    }
    CardGroup {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_provider_page_name)) },
        ) {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { onEdit(provider.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        formItem(
            headlineContent = { Text("Cookie") },
            supportingContent = {
                Text(
                    "点上面的「一键登录」在应用内登录，Cookie 会自动抓回来存好，不用手动复制。" +
                        "备用方式是：在浏览器里登录后复制 Cookie，再点下面的按钮自动挑出需要的键。" +
                        "至少需要 __Secure-1PSID 与 __Secure-1PSIDTS；有 SAPISID 会额外带认证头。" +
                        "注意这是复用浏览器会话的非公开接口，Google 随时可能收紧风控。"
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = provider.cookie,
                    onValueChange = { onEdit(provider.copy(cookie = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    placeholder = { Text("__Secure-1PSID=...; __Secure-1PSIDTS=...; SAPISID=...") },
                )
                // v1016：主路径 = 应用内一键登录（GeminiWebLoginActivity 抓 Cookie）。
                // 下面这一行「浏览器登录 / 粘贴 Cookie」两个按钮**不许删**：Google 对嵌入式浏览器的
                // 风控随时可能收紧，一键登录哪天失效，用户还得靠备用方式把 Cookie 弄进来。
                // v1016：这里必须按**意图**分按钮，不能一个按钮两用（审查位抓到的洞）：
                // 「一键登录」= 进来就抓，池子里已有登录态时一步到位；
                // 「重新登录 / 换账号」= 先清掉内置浏览器的旧登录态，逼 Google 真的把登录表单给你，
                // 否则轮询会把上次那颗 Cookie 当成本次成果抓走，第二个账号根本没机会登。
                if (provider.cookie.isBlank()) {
                    Button(
                        onClick = { webLoginLauncher.launch(GeminiWebLoginActivity.createIntent(context)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("一键登录 Google") }
                } else {
                    Button(
                        onClick = {
                            webLoginLauncher.launch(
                                GeminiWebLoginActivity.createIntent(context, requireFreshLogin = true),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重新登录 / 换账号（先清掉旧登录态）") }
                    OutlinedButton(
                        onClick = { webLoginLauncher.launch(GeminiWebLoginActivity.createIntent(context)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("一键续期（复用已登录状态，不重登）") }
                }
                OutlinedButton(
                    onClick = {
                        webLoginLauncher.launch(
                            GeminiWebLoginActivity.createIntent(
                                context,
                                GeminiWebLogin.START_URL_YOUTUBE_BRIDGE,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("备用登录入口：先登 YouTube，登录后自动跳回")
                }
                // v1007：把「打开浏览器登录 → 复制 → 回来粘贴」压成两个按钮。
                // 剪贴板里可能是一整段请求头、一段 JSON、或者换行分隔，统一由
                // normalizeGeminiWebCookie 挑出 PSID / PSIDTS / SAPISID，其它键丢掉。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            // v1016：补 FLAG_ACTIVITY_NEW_TASK，并把真实异常显示出来。原来只报
                            // "没有找到可用的浏览器"——设置页从弹窗里打开时这个 context 不是 Activity，
                            // 安卓抛的是 "requires the FLAG_ACTIVITY_NEW_TASK flag"，被那句谎话盖掉，
                            // 用户只会看到"点了没反应"，永远查不到原因。
                            val failure = runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        "https://gemini.google.com/app".toUri(),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.exceptionOrNull()
                            if (failure != null) {
                                toaster.show(
                                    "打不开浏览器：${failure.message ?: failure.javaClass.simpleName}",
                                    type = ToastType.Error,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("浏览器登录（备用）")
                    }
                    Button(
                        onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java)
                                ?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.text
                                ?.toString()
                            when {
                                clip.isNullOrBlank() -> toaster.show(
                                    "剪贴板里没有内容",
                                    type = ToastType.Warning,
                                )

                                else -> {
                                    val normalized = normalizeGeminiWebCookie(clip)
                                    if (normalized == null) {
                                        toaster.show(
                                            "没找到 __Secure-1PSID，请复制完整 Cookie",
                                            type = ToastType.Error,
                                        )
                                    } else {
                                        onEdit(provider.copy(cookie = normalized))
                                        toaster.show(
                                            "已填入 ${normalized.split("; ").size} 个键",
                                            type = ToastType.Success,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("粘贴 Cookie（备用）")
                    }
                }
            }
        }
        item(
            headlineContent = { Text("当前登录态") },
            supportingContent = {
                val present = GeminiWebLogin.presentKeys(provider.cookie)
                Text(
                    if (present.isEmpty()) {
                        "还没有登录态：按上面「一键登录 Google」，或用备用方式粘贴 Cookie。"
                    } else {
                        "已有：" + present.joinToString("、") +
                            if (GeminiWebLogin.KEY_SAPISID in GeminiWebLogin.missingKeys(provider.cookie)) {
                                "（缺 SAPISID 不影响日常调用）"
                            } else {
                                ""
                            }
                    },
                )
            },
        )
        // v1016：Cookie 与浏览器标识必须同源 —— 这是探测位取证里对实现影响最大的一条。
        // 一键登录会自动填进来；手动粘贴的用户如果一直 403，把本机浏览器的 UA 填进来就能对上。
        formItem(
            headlineContent = { Text("浏览器标识（留空用内置默认）") },
            supportingContent = {
                Text(
                    "网页会话是和「登录时用的浏览器」绑在一起的，标识对不上会被判定成会话被搬走。" +
                        "用一键登录不用管这里；手动粘贴还一直 403 的话，把你浏览器的 User Agent 填进来。"
                )
            },
        ) {
            OutlinedTextField(
                value = provider.userAgent,
                onValueChange = { onEdit(provider.copy(userAgent = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                placeholder = { Text("Mozilla/5.0 (Linux; Android 14; Pixel 8) ... Chrome/...") },
            )
        }
        item(
            onClick = {
                val failure = runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                }.exceptionOrNull()
                if (failure != null) {
                    toaster.show(
                        "清除失败：${failure.message ?: failure.javaClass.simpleName}",
                        type = ToastType.Error,
                    )
                } else {
                    toaster.show(
                        "内置浏览器的登录状态已清除（供应商里已存好的 Cookie 不受影响）",
                        type = ToastType.Info,
                    )
                }
            },
            headlineContent = { Text("清除内置浏览器登录状态") },
            supportingContent = { Text("换谷歌账号时用。清掉的是登录页记住的登录态，不会清掉已经保存好的 Cookie。") },
        )
        formItem(
            headlineContent = { Text("构建标签（可选）") },
            supportingContent = { Text("留空会自动从 Gemini 页面读取。只有协议失效、自动读取失败时才需要手动填。") },
        ) {
            OutlinedTextField(
                value = provider.buildLabel,
                onValueChange = { onEdit(provider.copy(buildLabel = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("boq_assistant-bard-web-server_...") },
            )
        }
    }
}
