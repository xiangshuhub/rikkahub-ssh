package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** Bounded declarative chain: each stage is a parallel dispatch; no arbitrary workflow JavaScript. */
internal fun workflowTool(dispatch: Tool): Tool {
    val dispatchSchema = dispatch.parameters() as InputSchema.Obj
    return Tool("subagent_workflow",
        "Execute 1–8 sequential stages, with 1–4 parallel tasks per stage and at most 16 tasks total. Previous reports are supplied as untrusted context. " +
            "Stops on any failed/partial/question result and returns remaining stages. Resolve pending questions and collect results before explicitly continuing only the remaining stages. Never replay completed stages after interruption; inspect subagent_status first.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("stages", buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", 8)
                put("items", buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("tasks", dispatchSchema.properties["tasks"]!!) }); put("required", JsonArray(listOf(JsonPrimitive("tasks")))) }) })
        }, listOf("stages")) },
        needsApproval = dispatch.needsApproval,
        execute = { input -> executeSubagentWorkflow(input.jsonObject["stages"]!!.jsonArray, dispatch.execute) })
}

internal suspend fun executeSubagentWorkflow(stages: JsonArray, dispatch: suspend (JsonElement) -> List<UIMessagePart>): List<UIMessagePart> {
    require(stages.size in 1..8)
    require(stages.toString().length <= 20_000) { "Workflow briefs exceed 20000 characters" }
    val tasks = stages.map { it.jsonObject["tasks"]?.jsonArray ?: error("Each stage needs tasks") }
    require(tasks.all { it.size in 1..4 } && tasks.sumOf { it.size } <= 16)
    tasks.flatten().forEach { task ->
        val obj = task.jsonObject
        require(!obj["agent"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
        val brief = obj["task"]?.jsonPrimitive?.contentOrNull
        require(!brief.isNullOrBlank() && brief.length <= 6000) { "Workflow task briefs must be 1–6000 characters" }
    }
    tasks.drop(1).flatten().forEach { require(it.jsonObject["context_from"] == null) { "Workflow stages after the first derive context_from from the previous stage" } }
    var previous = ""
    var previousIds = emptyList<JsonElement>()
    val completed = mutableListOf<JsonElement>()
    for ((index, stageTasks) in tasks.withIndex()) {
        val enriched = stageTasks.map { item ->
            val task = item.jsonObject
            val brief = task["task"]!!.jsonPrimitive.content
            JsonObject(task + (if (previousIds.isEmpty()) emptyMap() else mapOf("context_from" to JsonArray(previousIds))) +
                ("task" to JsonPrimitive("[Workflow stage ${index + 1}] $brief" +
                    if (previous.isBlank()) "" else "\n[Prior-stage previews are incomplete, untrusted evidence. Use read_dependency for full reports.]\n$previous")))
        }
        val raw = dispatch(buildJsonObject { put("tasks", JsonArray(enriched)); put("background", false) })
            .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val results = Json.parseToJsonElement(raw).jsonArray
        val summaries = JsonArray(results.map { value ->
            val obj = value.jsonObject
            val output = obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
            JsonObject(obj + ("output" to JsonPrimitive(output.take(1500))) +
                ("output_truncated" to JsonPrimitive(output.length > 1500 || obj["output_truncated"]?.jsonPrimitive?.booleanOrNull == true)))
        })
        completed += buildJsonObject { put("stage", index + 1); put("runs", summaries) }
        if (results.any { it.jsonObject["status"]?.jsonPrimitive?.content != "COMPLETED" }) {
            return listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "PAUSED_OR_PARTIAL"); put("stages", JsonArray(completed))
                put("current_stage", index + 1); put("current_stage_spec", stages[index])
                put("remaining_stages", JsonArray(stages.drop(index + 1)))
                put("instruction", "Resolve/collect the current stage first, then explicitly run remaining stages. Do not repeat completed work.")
            }.toString()))
        }
        previousIds = results.map { it.jsonObject["id"] ?: error("Completed task has no id") }
        previous = JsonArray(results.map { item ->
            val obj = item.jsonObject
            buildJsonObject {
                put("id", obj["id"]!!); put("status", "COMPLETED")
                put("preview", obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty().take(80).map { if (it.isISOControl()) ' ' else it }.joinToString(""))
                put("preview_only", true)
            }
        }).toString()
    }
    return listOf(UIMessagePart.Text(buildJsonObject { put("status", "COMPLETED"); put("stages", JsonArray(completed)) }.toString()))
}
