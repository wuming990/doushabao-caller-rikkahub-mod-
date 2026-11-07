package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.parseAgentInteger

/** v1024：六项数字改手填；只在确认合法输入后保存，取消不改设置。 */
@Composable
internal fun AgentNumberInput(
    title: String,
    value: Int,
    valueLabel: String,
    zeroAllowed: Boolean,
    onSave: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(value.toString()) }
    var showInputError by remember { mutableStateOf(false) }
    val currentOnSave by rememberUpdatedState(onSave)

    TextButton(
        onClick = {
            input = value.toString()
            showInputError = false
            showDialog = true
        },
    ) {
        Text(valueLabel)
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        showInputError = false
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                if (showInputError) {
                                    R.string.agent_numeric_input_error
                                } else if (zeroAllowed) {
                                    R.string.agent_numeric_input_hint
                                } else {
                                    R.string.agent_numeric_positive_hint
                                }
                            )
                        )
                    },
                    isError = showInputError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = parseAgentInteger(input, zeroAllowed)
                        if (parsed == null) {
                            showInputError = true
                        } else {
                            currentOnSave(parsed)
                            showDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
