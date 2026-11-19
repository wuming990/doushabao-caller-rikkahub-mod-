package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.gemini.GeminiAccountRepository
import me.rerere.rikkahub.data.gemini.GeminiRtImportError
import me.rerere.rikkahub.data.gemini.GeminiRtImportReport

// v1038：凭据只在本次弹窗内存中暂存，不放rememberSaveable、日志或导入结果。
@Composable
internal fun GeminiRtImportDialog(
    repository: GeminiAccountRepository,
    onDismiss: () -> Unit,
    onImported: suspend () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importedCallback by rememberUpdatedState(onImported)
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<GeminiRtImportReport?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val busy = reading || importing
    val dismiss = {
        operation?.cancel()
        input = ""
        onDismiss()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            operation = scope.launch {
                reading = true
                input = ""
                message = null
                report = null
                try {
                    input = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: throw java.io.IOException("Cannot read selected file")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = R.string.gemini_rt_file_failed
                } finally {
                    reading = false
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.gemini_rt_import_title)) },
        text = {
            // This is a separate, height-bounded dialog, not a second scroll view in the provider page.
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.gemini_rt_import_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; report = null; message = null },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.gemini_rt_input_label)) },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { filePicker.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")) },
                    ) { Text(stringResource(R.string.gemini_rt_choose_file)) }
                    TextButton(enabled = !busy, onClick = { visible = !visible }) {
                        Text(stringResource(if (visible) R.string.gemini_rt_hide_input else R.string.gemini_rt_show_input))
                    }
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (reading) stringResource(R.string.gemini_rt_reading_file)
                        else stringResource(R.string.gemini_rt_progress, completed, total),
                    )
                    Text(stringResource(R.string.gemini_rt_stop_note), style = MaterialTheme.typography.bodySmall)
                }
                message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                report?.let { result ->
                    Text(stringResource(R.string.gemini_rt_summary, result.successCount, result.failureCount))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(result.items, key = { it.position }) { item ->
                            if (item.error == null) {
                                Text(stringResource(R.string.gemini_rt_item_success, item.position, item.accountLabel.orEmpty()))
                            } else {
                                val reason = stringResource(geminiRtErrorResource(item.error))
                                val status = item.httpStatus?.let { stringResource(R.string.gemini_rt_http_status, it) }.orEmpty()
                                Text(
                                    stringResource(R.string.gemini_rt_item_failure, item.position, reason + status),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!busy) {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val pasted = input
                        input = ""
                        operation = scope.launch {
                            importing = true
                            completed = 0
                            total = 0
                            report = null
                            message = null
                            try {
                                val result = repository.importRefreshTokens(pasted) { done, count ->
                                    scope.launch { completed = done; total = count }
                                }
                                report = result
                                if (result.successCount > 0) {
                                    try {
                                        importedCallback()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // Accounts are already saved; a catalog error must not relabel import as failed.
                                        message = R.string.gemini_rt_models_failed
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                message = R.string.gemini_rt_error_unknown
                            } finally {
                                importing = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.gemini_rt_start_import)) }
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) {
                Text(stringResource(if (busy) R.string.gemini_rt_stop else R.string.gemini_rt_close))
            }
        },
    )
}

private fun geminiRtErrorResource(error: GeminiRtImportError): Int = when (error) {
    GeminiRtImportError.EMPTY_INPUT -> R.string.gemini_rt_error_empty
    GeminiRtImportError.INVALID_JSON -> R.string.gemini_rt_error_json
    GeminiRtImportError.MISSING_REFRESH_TOKEN -> R.string.gemini_rt_error_missing
    GeminiRtImportError.UNSUPPORTED_FORMAT -> R.string.gemini_rt_error_format
    GeminiRtImportError.REFRESH_FAILED -> R.string.gemini_rt_error_refresh
    GeminiRtImportError.INVALID_TOKEN_RESPONSE -> R.string.gemini_rt_error_response
    GeminiRtImportError.IDENTITY_FAILED -> R.string.gemini_rt_error_identity
    GeminiRtImportError.PROJECT_FAILED -> R.string.gemini_rt_error_project
    GeminiRtImportError.STORAGE_FAILED -> R.string.gemini_rt_error_storage
    GeminiRtImportError.NETWORK_FAILED -> R.string.gemini_rt_error_network
    GeminiRtImportError.UNKNOWN -> R.string.gemini_rt_error_unknown
}
