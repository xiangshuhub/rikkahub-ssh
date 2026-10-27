package me.rerere.workspace

/** A single child name, never a path or a shell fragment. */
fun validWorkspaceEntryName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 } &&
        name.toByteArray(Charsets.UTF_8).size <= 255

/** No mkdir -p, truncate, or overwrite: races with another creator fail closed. */
fun workspaceCreateEntryCommand(name: String, directory: Boolean): String {
    require(validWorkspaceEntryName(name)) { "Invalid file or folder name" }
    val path = "./$name".shellQuote()
    return if (directory) "mkdir -- $path" else "set -C; : > $path"
}
