package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

const val CHAT_ASSISTANT_UUID = "ae5ccf46-e18b-4d25-820a-a19ef1100101"
private const val LEGACY_EMPTY_UUID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
private const val LEGACY_GENERAL_UUID = "3d47790c-c415-4b90-9388-751128adb0a0"
val CHAT_ASSISTANT_ID = Uuid.parse(CHAT_ASSISTANT_UUID)
val RETIRED_ASSISTANT_IDS = setOf(Uuid.parse(LEGACY_EMPTY_UUID), Uuid.parse(LEGACY_GENERAL_UUID))

// Read-time grouping only: never rewrite/delete legacy conversations, folders or config snapshots.
const val CHAT_ASSISTANT_HISTORY_FILTER = "(assistant_id = :assistantId OR (:assistantId = '$CHAT_ASSISTANT_UUID' AND assistant_id IN ('$LEGACY_EMPTY_UUID', '$LEGACY_GENERAL_UUID')))"
fun assistantHistoryIds(id: Uuid): List<String> =
    (if (id == CHAT_ASSISTANT_ID) listOf(id) + RETIRED_ASSISTANT_IDS else listOf(id)).map(Uuid::toString)
fun visibleAssistantId(id: Uuid): Uuid = if (id in RETIRED_ASSISTANT_IDS) CHAT_ASSISTANT_ID else id

/** Preserve complete user-edited legacy profiles privately for old conversations and settings backups. */
internal fun Settings.retireOldBuiltIns(): Settings = copy(
    assistantId = visibleAssistantId(assistantId),
    retiredAssistantProfiles = (assistants.filter { it.id in RETIRED_ASSISTANT_IDS } + retiredAssistantProfiles)
        .filter { it.id in RETIRED_ASSISTANT_IDS }.distinctBy { it.id },
    assistants = assistants.filterNot { it.id in RETIRED_ASSISTANT_IDS },
)
