package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.preferences.DEFAULT_AUTO_APPROVE_TOOLS
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

@Composable
fun SettingToolApprovalsPage() {
    val context = LocalContext.current
    val preferences = remember(context) { ToolApprovalPreferences(context) }
    val autoApprove by preferences.autoApproveFlow.collectAsStateWithLifecycle(DEFAULT_AUTO_APPROVE_TOOLS)
    val grants by preferences.grantsFlow.collectAsStateWithLifecycle(emptySet())
    val knownTools by preferences.knownToolsFlow.collectAsStateWithLifecycle(emptySet())
    val tools = remember(knownTools, grants) { (knownTools + grants).sorted() }
    val managementPermissions by preferences.managementPermissionsFlow.collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmEnable by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tool_approvals_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("automaticApproval") {
                CardGroup(Modifier.fillMaxWidth()) {
                    switchItem(
                        checked = autoApprove,
                        onCheckedChange = { enabled ->
                            if (enabled) confirmEnable = true
                            else scope.launch { preferences.setAutoApprove(false) }
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03) },
                        headlineContent = { Text(stringResource(R.string.tool_approvals_auto)) },
                        supportingContent = {
                            Text(stringResource(if (autoApprove) R.string.tool_approvals_status_on else R.string.tool_approvals_status_off))
                        },
                    )
                }
            }
            item("warning") {
                Text(
                    text = stringResource(R.string.tool_approvals_warning),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item("managementHeading") {
                Text(stringResource(R.string.app_management_title), modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            item("managementPermissions") {
                CardGroup(Modifier.fillMaxWidth()) {
                    listOf(
                        "assistants" to R.string.app_management_assistants,
                        "workspace_bindings" to R.string.app_management_workspace_bindings,
                        "skills" to R.string.app_management_skills,
                        "mcp" to R.string.app_management_mcp,
                    ).forEach { (category, label) ->
                        switchItem(
                            checked = category in managementPermissions,
                            onCheckedChange = { enabled -> scope.launch { preferences.setManagementPermission(category, enabled) } },
                            headlineContent = { Text(stringResource(label)) },
                        )
                    }
                }
            }
            item("managementDescription") {
                Text(stringResource(R.string.app_management_description), modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item("individualHeading") {
                Text(
                    text = stringResource(R.string.tool_approvals_individual),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item("individualGrants") {
                CardGroup(Modifier.fillMaxWidth()) {
                    if (tools.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.tool_approvals_no_tools)) },
                            supportingContent = { Text(stringResource(R.string.tool_approvals_empty)) },
                        )
                    } else {
                        tools.forEach { name ->
                            switchItem(
                                checked = name in grants,
                                onCheckedChange = { enabled ->
                                    scope.launch { preferences.setGranted(name, enabled) }
                                },
                                headlineContent = { Text(name) },
                                supportingContent = { Text(stringResource(R.string.tool_approvals_always)) },
                            )
                        }
                    }
                }
            }
            item("scope") {
                Text(
                    text = stringResource(R.string.tool_approvals_scope),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (grants.isNotEmpty()) {
                item("revokeAll") {
                    CardGroup(Modifier.fillMaxWidth()) {
                        item(
                            onClick = { scope.launch { preferences.revokeAll() } },
                            leadingContent = {
                                CardGroupIcon(
                                    HugeIcons.Delete01,
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            },
                            headlineContent = {
                                Text(stringResource(R.string.tool_approvals_revoke_all), color = MaterialTheme.colorScheme.error)
                            },
                        )
                    }
                }
            }
        }
    }
    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(stringResource(R.string.tool_approvals_auto)) },
            text = { Text(stringResource(R.string.tool_approvals_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnable = false
                    scope.launch { preferences.setAutoApprove(true) }
                }) { Text(stringResource(R.string.tool_approvals_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) {
                    Text(stringResource(R.string.tool_approvals_cancel))
                }
            },
        )
    }
}
