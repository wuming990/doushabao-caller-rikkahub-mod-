package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.highlight.LocalCodeHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.uuid.Uuid
import me.rerere.ui.components.SelectTextField

private val jsonLenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

private val COMMON_HEADER_NAMES = listOf(
    "User-Agent",
    "HTTP-Referer",
    "X-Title",
    "Referer",
    "Origin",
    "Accept-Language",
    "Cookie",
    "anthropic-beta",
    "OpenAI-Organization",
    "OpenAI-Project",
)

// 按已输入内容过滤常用请求头，没有匹配(或已完整输入)时展示全部
private fun commonHeaderNames(input: String): List<String> {
    val keyword = input.trim()
    val matched = COMMON_HEADER_NAMES.filter {
        it.contains(keyword, ignoreCase = true) && !it.equals(keyword, ignoreCase = true)
    }
    return matched.ifEmpty { COMMON_HEADER_NAMES }
}

@Composable
fun CustomHeaders(headers: List<CustomHeader>, onUpdate: (List<CustomHeader>) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader(
            title = stringResource(R.string.assistant_page_custom_headers),
            action = {
                SectionAddButton(
                    onClick = {
                        val updatedHeaders = headers.toMutableList()
                        updatedHeaders.add(CustomHeader("", ""))
                        onUpdate(updatedHeaders)
                    },
                    contentDescription = stringResource(R.string.assistant_page_add_header),
                )
            },
        )

        CardGroup {
            headers.forEachIndexed { index, header ->
                formItem {
                    var headerName by remember(header.name) { mutableStateOf(header.name) }
                    var headerValue by remember(header.value) { mutableStateOf(header.value) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val updateHeaderName = { name: String ->
                                headerName = name
                                val updatedHeaders = headers.toMutableList()
                                updatedHeaders[index] = updatedHeaders[index].copy(name = name.trim())
                                onUpdate(updatedHeaders)
                            }
                            SelectTextField(
                                value = headerName,
                                options = commonHeaderNames(headerName),
                                onValueChange = updateHeaderName,
                                onOptionSelected = updateHeaderName,
                                label = { Text(stringResource(R.string.assistant_page_header_name)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = headerValue,
                                onValueChange = {
                                    headerValue = it
                                    val updatedHeaders = headers.toMutableList()
                                    updatedHeaders[index] =
                                        updatedHeaders[index].copy(value = it.trim())
                                    onUpdate(updatedHeaders)
                                },
                                label = { Text(stringResource(R.string.assistant_page_header_value)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        IconButton(onClick = {
                            val updatedHeaders = headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            onUpdate(updatedHeaders)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_header)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomBodies(
    customBodies: List<CustomBody>,
    onUpdate: (List<CustomBody>) -> Unit,
    providers: List<ProviderSetting> = emptyList(),
) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader(
            title = stringResource(R.string.assistant_page_custom_bodies),
            action = {
                SectionAddButton(
                    onClick = {
                        val updatedBodies = customBodies.toMutableList()
                        updatedBodies.add(CustomBody("", JsonPrimitive("")))
                        onUpdate(updatedBodies)
                    },
                    contentDescription = stringResource(R.string.assistant_page_add_body),
                )
            },
        )

        CardGroup {
            customBodies.forEachIndexed { index, body ->
                formItem {
                    var bodyKey by remember(body.key) { mutableStateOf(body.key) }
                    var bodyValueString by remember(body.value) {
                        mutableStateOf(jsonLenient.encodeToString(JsonElement.serializer(), body.value))
                    }
                    var jsonParseError by remember { mutableStateOf<String?>(null) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = bodyKey,
                                onValueChange = {
                                    bodyKey = it
                                    val updatedBodies = customBodies.toMutableList()
                                    updatedBodies[index] = updatedBodies[index].copy(key = it.trim())
                                    onUpdate(updatedBodies)
                                },
                                label = { Text(stringResource(R.string.assistant_page_body_key)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = bodyValueString,
                                onValueChange = { newString ->
                                    bodyValueString = newString
                                    try {
                                        val newJsonValue = jsonLenient.parseToJsonElement(newString)
                                        val updatedBodies = customBodies.toMutableList()
                                        updatedBodies[index] =
                                            updatedBodies[index].copy(value = newJsonValue)
                                        onUpdate(updatedBodies)
                                        jsonParseError = null
                                    } catch (e: Exception) {
                                        jsonParseError =
                                            context.getString(
                                                R.string.assistant_page_invalid_json,
                                                e.message?.take(100) ?: ""
                                            )
                                    }
                                },
                                label = { Text(stringResource(R.string.assistant_page_body_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                isError = jsonParseError != null,
                                supportingText = {
                                    if (jsonParseError != null) {
                                        Text(jsonParseError!!)
                                    }
                                },
                                minLines = 3,
                                maxLines = 5,
                                visualTransformation = HighlightCodeVisualTransformation(
                                    language = "json",
                                    highlighter = LocalCodeHighlighter.current,
                                    darkMode = LocalDarkMode.current
                                ),
                                textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
                            )

                            if (providers.isNotEmpty()) {
                                CustomBodyProviderScope(
                                    providers = providers,
                                    excludedProviderIds = body.excludedProviderIds,
                                    onUpdate = { newSelection ->
                                        val updatedBodies = customBodies.toMutableList()
                                        updatedBodies[index] =
                                            updatedBodies[index].copy(excludedProviderIds = newSelection)
                                        onUpdate(updatedBodies)
                                    },
                                )
                            }
                        }
                        IconButton(onClick = {
                            val updatedBodies = customBodies.toMutableList()
                            updatedBodies.removeAt(index)
                            onUpdate(updatedBodies)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_body)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 每条自定义 Body 的排除范围选择。
 *
 * 默认对所有已启用供应商生效；只有不支持该字段的少数供应商需要点选排除。
 * - null / 空集合: 不排除任何供应商（所有供应商都会带上该字段）
 * - 非空集合: 列表里被点中的供应商不会带上该字段
 */
@Composable
private fun CustomBodyProviderScope(
    providers: List<ProviderSetting>,
    excludedProviderIds: Set<Uuid>?,
    onUpdate: (Set<Uuid>?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.assistant_page_body_scope),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = stringResource(R.string.assistant_page_body_scope_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CustomBodyScopeChip(
                label = stringResource(R.string.assistant_page_body_scope_all),
                selected = excludedProviderIds.isNullOrEmpty(),
                exclusion = false,
                onClick = { onUpdate(null) },
            )
            providers.forEach { provider ->
                val excluded = excludedProviderIds?.contains(provider.id) == true
                CustomBodyScopeChip(
                    label = provider.name.ifBlank { provider.id.toString().take(8) },
                    selected = excluded,
                    exclusion = true,
                    onClick = {
                        val current = excludedProviderIds ?: emptySet()
                        val next = if (excluded) current - provider.id else current + provider.id
                        onUpdate(next.ifEmpty { null })
                    },
                )
            }
        }
    }
}

@Composable
private fun CustomBodyScopeChip(
    label: String,
    selected: Boolean,
    exclusion: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        if (exclusion) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
