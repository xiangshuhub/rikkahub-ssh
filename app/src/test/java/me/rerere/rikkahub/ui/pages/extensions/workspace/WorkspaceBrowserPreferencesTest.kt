package me.rerere.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceBrowserPreferencesTest {
    private val data = mutableMapOf<String, String>()
    private fun store() = WorkspaceBrowserPreferences({ data[it] }, { key, value -> data[key] = value })

    @Test fun firstVisitHasNoHistoryOrBookmarks() {
        assertNull(store().lastLocation("a"))
        assertTrue(store().bookmarks("a").isEmpty())
    }

    @Test fun lastLocationSurvivesRecreationAndIsWorkspaceScoped() {
        val location = WorkspaceBrowserLocation("LINUX", "home/user/project")
        store().saveLocation("a", location)
        assertEquals(location, store().lastLocation("a"))
        assertNull(store().lastLocation("b"))
        store().saveLocation("b", WorkspaceBrowserLocation("FILES", "src"))
        assertEquals(location, store().lastLocation("a"))
    }

    @Test fun bothAreaRootsAreRememberedDistinctly() {
        store().saveLocation("a", WorkspaceBrowserLocation("LINUX"))
        assertEquals("LINUX", store().lastLocation("a")?.area)
        store().saveLocation("a", WorkspaceBrowserLocation())
        assertEquals(WorkspaceBrowserLocation(), store().lastLocation("a"))
    }

    @Test fun addRenameAndRemoveDoNotChangeHistoryOrOtherWorkspaces() {
        val location = WorkspaceBrowserLocation("FILES", "项目/my folder")
        val other = WorkspaceBrowserLocation("LINUX", "tmp")
        store().saveLocation("a", other)
        store().saveBookmark("a", location, "项目")
        store().saveBookmark("b", location, "独立收藏")
        store().saveBookmark("a", location, " 新名称 ")
        assertEquals(listOf(WorkspaceBookmark(location, "新名称")), store().bookmarks("a"))
        assertEquals(other, store().lastLocation("a"))
        store().removeBookmark("a", location)
        assertTrue(store().bookmarks("a").isEmpty())
        assertEquals("独立收藏", store().bookmarks("b").single().name)
    }

    @Test fun equalPathsInDifferentAreasAreDifferentBookmarks() {
        store().saveBookmark("a", WorkspaceBrowserLocation("FILES", "src"), "工作空间源码")
        store().saveBookmark("a", WorkspaceBrowserLocation("LINUX", "src"), "根目录源码")
        assertEquals(2, store().bookmarks("a").size)
    }

    @Test fun corruptHistoryAndBookmarksFailSafely() {
        data["last:a"] = "not json"
        data["bookmarks:a"] = "{}"
        assertNull(store().lastLocation("a"))
        assertTrue(store().bookmarks("a").isEmpty())
        data["last:a"] = """{"area":"OTHER","path":"secret"}"""
        assertNull(store().lastLocation("a"))
    }

    @Test fun invalidPathsCannotBeStoredButSpacesAndUnicodeArePreserved() {
        for (path in listOf("/etc", "../etc", "foo/../bar", "foo//bar", "foo/", "x\u0000y")) {
            assertFalse(WorkspaceBrowserLocation("FILES", path).isValid())
        }
        val location = WorkspaceBrowserLocation("FILES", "中文/ folder ")
        store().saveLocation("a", location)
        assertEquals(location, store().lastLocation("a"))
    }

    @Test fun blankAndExcessiveLabelsAreRejected() {
        for (name in listOf(" ", "a".repeat(101))) {
            assertTrue(runCatching { store().saveBookmark("a", WorkspaceBrowserLocation(), name) }.isFailure)
        }
        assertTrue(store().bookmarks("a").isEmpty())
    }
}
