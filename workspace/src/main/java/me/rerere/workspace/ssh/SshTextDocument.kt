package me.rerere.workspace.ssh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Encoding and revision snapshot for the human file editor, adapted from rikkahub-ssh. */
data class SshTextDocument(
    val text: String,
    val revision: String,
    val charsetName: String = "UTF-8",
    val bom: List<Byte> = emptyList(),
    val crlf: Boolean = false,
) {
    fun encode(edited: String): ByteArray {
        val normalized = if (crlf) edited.replace("\r\n", "\n").replace("\n", "\r\n") else edited
        val buffer = Charset.forName(charsetName).newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(normalized))
        return bom.toByteArray() + ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}

fun textRevision(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

fun decodeSshText(bytes: ByteArray): SshTextDocument {
    require(bytes.size <= 2 * 1024 * 1024) { "File too large for text editing" }
    val (charset, length) = when {
        bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) -> "UTF-8" to 3
        bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> "UTF-16LE" to 2
        bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> "UTF-16BE" to 2
        else -> "UTF-8" to 0
    }
    val text = Charset.forName(charset).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, length, bytes.size - length)).toString()
    require('\u0000' !in text) { "Binary or unsupported encoding; download instead" }
    val crlf = "\r\n" in text && '\n' !in text.replace("\r\n", "")
    return SshTextDocument(text, textRevision(bytes), charset, bytes.take(length), crlf)
}
