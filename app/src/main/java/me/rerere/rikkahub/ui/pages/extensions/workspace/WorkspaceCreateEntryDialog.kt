package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.workspace.validWorkspaceEntryName

@Composable
internal fun WorkspaceCreateEntryDialog(
    directory: Boolean,
    onCreate: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = validWorkspaceEntryName(name)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (directory) R.string.workspace_new_folder else R.string.workspace_new_file)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.workspace_entry_name)) },
                singleLine = true,
                enabled = !busy,
                isError = error != null || (name.isNotEmpty() && !valid),
                supportingText = {
                    Text(error ?: stringResource(if (name.isNotEmpty() && !valid)
                        R.string.workspace_entry_invalid else R.string.workspace_entry_create_hint))
                },
            )
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                val requested = name
                busy = true
                scope.launch {
                    try { onCreate(requested); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Create failed" }
                    finally { busy = false }
                }
            }) { Text(stringResource(if (busy) R.string.workspace_entry_creating else R.string.workspace_entry_create)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
