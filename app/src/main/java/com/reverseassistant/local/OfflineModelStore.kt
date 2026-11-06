package com.reverseassistant.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object OfflineModelStore {
    private const val MAX_MODEL_BYTES = 8L * 1024L * 1024L * 1024L

    fun import(context: Context, uri: Uri): File {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "local-model.gguf"
        require(displayName.endsWith(".gguf", ignoreCase = true)) { "Choose a GGUF model file." }
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        require(size <= MAX_MODEL_BYTES) { "Model exceeds the 8 GB import limit." }
        val free = StatFs(context.filesDir.absolutePath).availableBytes
        require(size < 0 || free > size + 256L * 1024L * 1024L) {
            "Not enough free app storage to copy this model safely."
        }

        val folder = File(context.filesDir, "offline-models").apply { mkdirs() }
        val destination = File(folder, "model-${UUID.randomUUID()}.gguf")
        try {
            val input = resolver.openInputStream(uri) ?: error("Could not open the selected model.")
            BufferedInputStream(input).use { source ->
                val magic = ByteArray(4)
                require(source.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
                    "This file does not have the GGUF header."
                }
                FileOutputStream(destination).use { output ->
                    output.write(magic)
                    val buffer = ByteArray(1024 * 1024)
                    var total = 4L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_MODEL_BYTES) { "Model exceeds the 8 GB import limit." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
}
