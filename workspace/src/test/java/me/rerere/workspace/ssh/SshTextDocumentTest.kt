package me.rerere.workspace.ssh

import org.junit.Assert.*
import org.junit.Test

class SshTextDocumentTest {
    @Test fun preservesUtf8BomAndCrlf() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "hello\r\n世界\r\n".toByteArray()
        val document = decodeSshText(bytes)
        assertArrayEquals(bytes, document.encode(document.text))
        assertTrue(document.crlf)
        assertTrue(String(document.encode("new\ntext")).contains("new\r\ntext"))
    }

    @Test fun preservesUtf16() {
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "你好".toByteArray(Charsets.UTF_16LE)
        val document = decodeSshText(bytes)
        assertEquals("UTF-16LE", document.charsetName)
        assertArrayEquals(bytes, document.encode(document.text))
    }

    @Test fun revisionChangesWithContent() {
        assertNotEquals(textRevision("one".toByteArray()), textRevision("two".toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBinary() { decodeSshText(byteArrayOf(0, 1, 2)) }

    @Test fun rejectsInvalidUtf8() {
        assertTrue(runCatching { decodeSshText(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
    }
}
