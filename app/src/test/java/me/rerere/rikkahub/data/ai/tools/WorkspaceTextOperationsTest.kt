package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTextOperationsTest {
    @Test fun editsUseOriginalSnapshotAndSupportUnsortedChanges() {
        assertEquals("ONE middle TWO", applyWorkspaceEdits("one middle two", listOf(
            WorkspaceTextEdit("two", "TWO"), WorkspaceTextEdit("one", "ONE"))))
        assertEquals("b c", applyWorkspaceEdits("a b", listOf(WorkspaceTextEdit("a", "b"), WorkspaceTextEdit("b", "c"))))
    }
    @Test fun ambiguousMissingAndOverlappingEditsAreRejected() {
        listOf(
            "aaa" to listOf(WorkspaceTextEdit("aa", "x")),
            "abc" to listOf(WorkspaceTextEdit("ab", "x"), WorkspaceTextEdit("bc", "y")),
            "abc" to listOf(WorkspaceTextEdit("abc", "x"), WorkspaceTextEdit("b", "y")),
            "abc" to listOf(WorkspaceTextEdit("", "x")),
            "abc" to listOf(WorkspaceTextEdit("a", "x"), WorkspaceTextEdit("missing", "y")),
            "  a" to listOf(WorkspaceTextEdit(" a ", "x")),
        ).forEach { (text, edits) -> assertTrue(runCatching { applyWorkspaceEdits(text, edits) }.isFailure) }
    }
    @Test fun pagesHaveContinuationWithoutLosingLines() {
        val first = workspaceTextPage("a\nb\nc", 1, 2)
        assertEquals("a\nb", first.text)
        assertEquals(3, first.totalLines)
        assertEquals(3, first.nextOffset)
        val last = workspaceTextPage("a\nb\nc", 3, 2)
        assertEquals("c", last.text)
        assertNull(last.nextOffset)
        assertEquals("", workspaceTextPage("").text)
    }
    @Test fun oversizedPagesStopAtLineBoundaryAndInvalidRangesFail() {
        val text = (1..100).joinToString("\n") { "中".repeat(1000) }
        val page = workspaceTextPage(text)
        assertTrue(page.text.toByteArray().size <= 48 * 1024)
        assertNotNull(page.nextOffset)
        assertTrue(runCatching { workspaceTextPage("a", 0) }.isFailure)
        assertTrue(runCatching { workspaceTextPage("a", 2) }.isFailure)
        assertTrue(runCatching { workspaceTextPage("a", 1, 0) }.isFailure)
        assertTrue(runCatching { workspaceTextPage("a".repeat(50 * 1024)) }.isFailure)
    }
}
