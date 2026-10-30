package me.rerere.rikkahub.subagent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import kotlin.uuid.Uuid

internal fun notifySubagents(context: Context, conversationId: Uuid, runs: List<SubagentRun>) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("subagent_results", "子代理任务", NotificationManager.IMPORTANCE_DEFAULT))
    val id = 5000 + (conversationId.hashCode() and Int.MAX_VALUE) % 1_000_000
    val intent = PendingIntent.getActivity(context, id, conversationIntent(context, conversationId.toString()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val waiting = runs.count { it.status == SubagentStatus.WAITING_INPUT }
    val notification = NotificationCompat.Builder(context, "subagent_results")
        .setSmallIcon(R.drawable.ic_stat_rikkahub)
        .setContentTitle(if (waiting > 0) "子代理有 $waiting 个待回答问题" else "${runs.size} 个子代理任务已结束")
        .setContentText("点击返回父对话查看任务卡片；通知不展示任务内容。")
        .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true).build()
    try { NotificationManagerCompat.from(context).notify(id, notification) } catch (_: SecurityException) { /* Permission revoked concurrently. */ }
}
