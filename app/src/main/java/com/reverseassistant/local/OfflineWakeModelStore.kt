package com.reverseassistant.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Bounded extraction for a user-selected Vosk offline speech model archive. */
object OfflineWakeModelStore {
    private const val MAX_ARCHIVE_BYTES = 250L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 500L * 1024L * 1024L
    private const val MAX_ENTRIES = 8000

    fun import(context: Context, uri: Uri): File {
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        require(size < 0 || size <= MAX_ARCHIVE_BYTES) { "Vosk model ZIP exceeds the 250 MB import limit." }
        val root = File(context.filesDir, "offline-wake-models/model-${UUID.randomUUID()}").apply { mkdirs() }
        var expanded = 0L
        var compressed = 0L
        var entries = 0
        try {
            val raw = context.contentResolver.openInputStream(uri) ?: error("Could not open the Vosk model ZIP.")
            val bounded = object : FilterInputStream(raw) {
                override fun read(): Int {
                    val value = super.read()
                    if (value >= 0) {
                        compressed++
                        require(compressed <= MAX_ARCHIVE_BYTES) { "Vosk model ZIP exceeds the 250 MB limit." }
                    }
                    return value
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (count > 0) {
                        compressed += count
                        require(compressed <= MAX_ARCHIVE_BYTES) { "Vosk model ZIP exceeds the 250 MB limit." }
                    }
                    return count
                }
            }
            ZipInputStream(bounded).use { zip ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ENTRIES) { "Vosk model archive contains too many files." }
                    val output = File(root, entry.name).canonicalFile
                    require(output.path.startsWith(root.canonicalPath + File.separator) || output == root) {
                        "Unsafe ZIP path rejected."
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { sink ->
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= MAX_EXPANDED_BYTES) { "Expanded Vosk model exceeds 500 MB." }
                                sink.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val modelRoot = sequenceOf(root).plus(root.walkTopDown().filter { it.isDirectory })
                .firstOrNull { File(it, "am/final.mdl").isFile && File(it, "conf/model.conf").isFile }
                ?: error("This ZIP does not contain a valid Vosk acoustic model.")
            return modelRoot
        } catch (error: Exception) {
            root.deleteRecursively()
            throw error
        }
    }
}
