package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption

const val WORKSPACE_ASSISTANT_PROMPT = "你是此 Linux 工作空间的运维与开发助手，遵循环境和项目约定完成任务。"

/** A fresh ordinary assistant, never a copy of the currently selected (possibly privileged) assistant. */
fun createWorkspaceAssistant(workspaceId: Uuid, name: String, id: Uuid = Uuid.random()): Assistant = Assistant(
    id = id,
    name = name,
    workspaceId = workspaceId,
    systemPrompt = WORKSPACE_ASSISTANT_PROMPT,
    localTools = listOf(LocalToolOption.AskUser),
)
