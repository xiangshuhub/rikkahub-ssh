package me.rerere.rikkahub.subagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.MessageQueue
import org.junit.Assert.*
import org.junit.Test

class SubagentEventQueueTest {
    @Test fun `automation events are deduplicated without merging ordinary user messages`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(UIMessagePart.Text("event")), automationKey = "run:1")
        queue.enqueue(listOf(UIMessagePart.Text("duplicate")), automationKey = "run:1")
        queue.enqueue(listOf(UIMessagePart.Text("user")))
        queue.enqueue(listOf(UIMessagePart.Text("user")))
        assertEquals(3, queue.state.value.messages.size)
        assertEquals("run:1", queue.takeNext()!!.automationKey)
    }

    @Test fun `enqueuing an event never unpauses a stopped queue`() {
        val queue = MessageQueue()
        queue.pause()
        queue.enqueue(listOf(UIMessagePart.Text("event")), automationKey = "run:1")
        assertNull(queue.takeNext())
        assertTrue(queue.state.value.paused)
    }

    @Test fun `event provenance survives persistence and editing`() {
        val message = UIMessage.user("automatic report").copy(automationKey = "child-event")
        assertEquals("child-event", Json.decodeFromString<UIMessage>(Json.encodeToString(message)).automationKey)
        val queue = MessageQueue()
        queue.enqueue(message.parts, automationKey = message.automationKey)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id, listOf(UIMessagePart.Text("edited report")))
        assertEquals("child-event", queue.state.value.messages.single().automationKey)
    }
}
