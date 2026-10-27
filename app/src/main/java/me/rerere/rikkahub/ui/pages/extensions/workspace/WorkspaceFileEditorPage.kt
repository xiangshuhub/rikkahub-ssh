package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.workspace.ssh.SshTextDocument
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ui.toast.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.rememberWebViewState
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 工作区文本文件编辑/预览页.
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val supportsPreview = extension in setOf("html", "htm", "svg")
    var showPreview by rememberSaveable(id, area, path) { mutableStateOf(supportsPreview) }

    // 不能用 rememberTextFieldState: 它会把全文存进 saved state Bundle, 大文件切后台时触发 TransactionTooLargeException (#1953).
    // 内容本就由下方 LaunchedEffect 从磁盘加载, 无需 saveable.
    val textState = remember(id, area, path) { TextFieldState() }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var original by remember(id, path) { mutableStateOf<SshTextDocument?>(null) }
    val context = LocalContext.current
    val navController = LocalNavController.current
    val drafts = remember(id, path) { WorkspaceEditorDraftStore(context, id, path) }
    var pendingDraft by remember(id, path) { mutableStateOf<WorkspaceEditorDraft?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = editable && original != null && textState.text.toString() != original?.text
    fun leave() {
        if (saving) return
        if (dirty) confirmLeave = true else navController.popBackStack()
    }
    BackHandler(enabled = dirty || saving) { leave() }

    LaunchedEffect(id, area, path) {
        loading = true
        loadError = null
        runCatching {
            if (editable) {
                val document = repository.readEditorDocument(id, path)
                original = document
                pendingDraft = drafts.read()
                document.text
            } else repository.readTextForPreview(id, area, path)
        }.onSuccess { content ->
            textState.setTextAndPlaceCursorAtEnd(content)
            loading = false
        }.onFailure {
            if (it is CancellationException) throw it
            loadError = it.message ?: "读取文件失败"
            loading = false
        }
    }

    pendingDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { pendingDraft = null },
            title = { Text("发现本地草稿") },
            text = { Text(if (draft.revision == original?.revision) "恢复上次保留的编辑内容？" else "远端文件已变化。可恢复草稿查看和复制，但保存会被冲突保护阻止，不会直接覆盖远端。") },
            confirmButton = { TextButton(onClick = {
                original = original?.copy(revision = draft.revision)
                textState.setTextAndPlaceCursorAtEnd(draft.text)
                showPreview = false
                pendingDraft = null
            }) { Text("恢复草稿") } },
            dismissButton = { TextButton(onClick = { pendingDraft = null }) { Text("使用远端内容") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { if (!saving) confirmLeave = false },
            title = { Text("有未保存的修改") },
            text = { Text("可保留本地草稿后离开，不会写入远端文件。") },
            confirmButton = { TextButton(enabled = !saving, onClick = {
                val document = original ?: return@TextButton
                val edited = textState.text.toString()
                saving = true
                scope.launch {
                    try {
                        drafts.save(WorkspaceEditorDraft(document.revision, edited))
                        confirmLeave = false
                        navController.popBackStack()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { toaster.show(e.message ?: "草稿保存失败", type = ToastType.Error) }
                    finally { saving = false }
                }
            }) { Text("保留草稿并返回") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { confirmLeave = false }) { Text("继续编辑") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(enabled = !saving, onClick = ::leave) { Text("返回") } },
                actions = {
                    if (supportsPreview && !loading && loadError == null) {
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Text(if (showPreview) "源码" else "预览")
                        }
                    }
                    if (editable && !loading && loadError == null) {
                        TextButton(
                            onClick = {
                                if (saving) return@TextButton
                                val document = original ?: return@TextButton
                                val edited = textState.text.toString()
                                saving = true
                                scope.launch {
                                    try {
                                        // Persist recovery content before the network write, including uncertain failures.
                                        drafts.save(WorkspaceEditorDraft(document.revision, edited))
                                        val saved = repository.saveEditorDocument(id, path, document, edited)
                                        original = saved
                                        textState.setTextAndPlaceCursorAtEnd(saved.text)
                                        drafts.clear()
                                        toaster.show("已保存", type = ToastType.Success)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        toaster.show(e.message ?: "保存失败，草稿已保留", type = ToastType.Error)
                                    } finally { saving = false }
                                }
                            },
                            enabled = !saving,
                        ) {
                            Text("Save")
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            showPreview -> WorkspaceWebPreview(
                content = textState.text.toString(),
                isSvg = extension == "svg",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )

            else -> TextField(
                state = textState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                readOnly = !editable || saving,
                lineLimits = TextFieldLineLimits.MultiLine(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = JetbrainsMono,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

@Composable
private fun WorkspaceWebPreview(
    content: String,
    isSvg: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = content,
        baseUrl = "https://workspace-preview.invalid/",
        mimeType = if (isSvg) "image/svg+xml" else "text/html",
        settings = {
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )
    WebView(state = state, modifier = modifier)
}
