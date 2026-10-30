package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.model.isAppManagementAssistant
import me.rerere.rikkahub.subagent.SubagentMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.pages.setting.components.SubagentSettingsContent
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingSubagentsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(title = { Text("子代理 · 全局设置") }, navigationIcon = { BackButton() },
                scrollBehavior = scroll, colors = CustomColors.topBarColors)
        },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (settings.init) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val ordinary = settings.assistants.filterNot { it.isAppManagementAssistant }
            var showOverrides by remember { mutableStateOf(false) }
            CardGroup(title = { Text("助手继承") }) {
                item(onClick = { showOverrides = !showOverrides },
                    headlineContent = { Text("所有普通助手默认跟随全局") },
                    supportingContent = { Text("${ordinary.count { it.subagentMode == SubagentMode.DISABLED }} 个助手已单独禁用。点击管理例外；应用管理助手始终禁用。") })
                if (showOverrides) ordinary.forEach { assistant ->
                    formItem(headlineContent = { Text(assistant.name.ifBlank { "默认助手" }) }) {
                        ConnectedChoiceButtons(options = SubagentMode.entries, selected = assistant.subagentMode,
                            onSelect = { mode -> vm.updateSettings { latest -> latest.copy(assistants = latest.assistants.map {
                                if (it.id == assistant.id) it.copy(subagentMode = mode) else it
                            }) } }) { mode, selected ->
                            ConnectedChoiceLabel(if (mode == SubagentMode.INHERIT) "跟随全局" else "禁用", selected)
                        }
                    }
                }
            }
            SubagentSettingsContent(
                config = settings.subagents,
                providers = settings.providers,
                assistants = settings.assistants + settings.retiredAssistantProfiles,
                legacyAssistants = settings.assistants + settings.retiredAssistantProfiles,
                onUpdate = { value -> vm.updateSettings { it.copy(subagents = value) } },
            )
        }
    }
}
