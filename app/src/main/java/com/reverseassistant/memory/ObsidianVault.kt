package com.reverseassistant.memory

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ObsidianVault {
    private const val PREFS = "reverse_assistant_vault"
    private const val TREE = "vault_tree_uri"

    fun saveTree(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TREE, uri.toString()).apply()
    }

    fun hasVault(context: Context) =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE, null).isNullOrBlank()

    fun readMemory(context: Context, maxChars: Int = 12000): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE, null)
            ?: return ""
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(raw)) ?: return ""
            val folder = root.findFile("Reverse Assistant") ?: return ""
            val file = folder.findFile("Assistant Memory.md") ?: return ""
            context.contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText().takeLast(maxChars)
            }.orEmpty()
        } catch (_: Exception) { "" }
    }

    fun appendMemory(context: Context, title: String, content: String): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE, null)
            ?: error("Choose your Obsidian vault first.")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(raw)) ?: error("Obsidian vault is unavailable.")
        val folder = root.findFile("Reverse Assistant") ?: root.createDirectory("Reverse Assistant")
            ?: error("Could not create a folder in the selected vault.")
        val file = folder.findFile("Assistant Memory.md") ?: folder.createFile("text/markdown", "Assistant Memory.md")
            ?: error("Could not create the memory note.")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val addition = "\n\n## ${title.replace("\n", " ").take(100)} — $stamp\n\n$content\n"
        context.contentResolver.openOutputStream(file.uri, "wa").use { stream ->
            requireNotNull(stream) { "Could not open the Obsidian note for writing." }
            stream.write(addition.toByteArray(Charsets.UTF_8))
        }
        return file.uri.toString()
    }
}
