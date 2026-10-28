package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun WorkspaceBookmarksDialog(
    bookmarks: List<WorkspaceBookmark>,
    current: WorkspaceBrowserLocation,
    canBookmark: Boolean,
    onOpen: (WorkspaceBrowserLocation) -> Unit,
    onSave: (WorkspaceBrowserLocation, String) -> Unit,
    onRemove: (WorkspaceBrowserLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<WorkspaceBrowserLocation?>(null) }
    var name by remember { mutableStateOf("") }
    fun edit(location: WorkspaceBrowserLocation, label: String) {
        editing = location
        name = label
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "收藏目录" else "目录收藏夹") },
        text = {
            val target = editing
            if (target != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(locationLabel(target))
                    OutlinedTextField(
                        value = name, onValueChange = { if (it.length <= 100) name = it },
                        label = { Text("名称") }, singleLine = true,
                    )
                }
            } else {
                Column {
                    TextButton(enabled = canBookmark, onClick = {
                        edit(current, bookmarks.firstOrNull { it.location == current }?.name
                            ?: current.path.substringAfterLast('/').ifBlank { locationLabel(current) })
                    }) { Text("收藏当前目录") }
                    if (bookmarks.isEmpty()) Text("暂无收藏。收藏仅属于当前工作空间。")
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(bookmarks, key = { "${it.location.area}:${it.location.path}" }) { bookmark ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Column(modifier = Modifier.weight(1f).clickable { onOpen(bookmark.location) }.padding(vertical = 12.dp)) {
                                    Text(bookmark.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(locationLabel(bookmark.location), style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { edit(bookmark.location, bookmark.name) }) { Text("改名") }
                                TextButton(onClick = { onRemove(bookmark.location) }) { Text("移除") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editing != null) {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    onSave(checkNotNull(editing), name)
                    editing = null
                }) { Text("保存") }
            } else TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            if (editing != null) TextButton(onClick = { editing = null }) { Text("取消") }
        },
    )
}

private fun locationLabel(location: WorkspaceBrowserLocation) =
    (if (location.area == "FILES") "工作空间" else "根目录") + " /" + location.path
