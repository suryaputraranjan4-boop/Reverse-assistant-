package com.reverseassistant

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Imports PMX/MMD model packages into app-private storage. Archives are never uploaded or committed.
 * Extraction is bounded and rejects path traversal. Rendering/rigging is a separate runtime capability.
 */
object ModelPackageImporter {
    data class Result(
        val folderName: String,
        val pmxCount: Int,
        val textureCount: Int,
        val nestedArchiveCount: Int,
        val expandedBytes: Long,
        val firstPmxPath: String
    ) {
        val summary: String get() =
            "$pmxCount PMX model(s), $textureCount texture(s), $nestedArchiveCount nested archive(s)"
    }

    private const val MAX_INPUT_BYTES = 300L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 600L * 1024L * 1024L
    private const val MAX_ENTRIES = 6000
    private const val MAX_DEPTH = 2

    fun import(context: Context, uri: Uri): Result {
        val root = File(context.filesDir, "imported-models").apply { mkdirs() }
        val folder = File(root, "model-${System.currentTimeMillis()}").apply { mkdirs() }
        val budget = Budget()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                extract(input, folder, folder, 0, budget)
            } ?: error("Could not open the selected model package.")
            val pmxFiles = folder.walkTopDown().filter { it.isFile && it.extension.equals("pmx", true) }.toList()
            val pmx = pmxFiles.size
            val firstPmx = pmxFiles.firstOrNull()
            val textures = folder.walkTopDown().count {
                it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "tga", "dds")
            }
            require(pmx > 0 && firstPmx != null) { "No PMX model was found in this archive. Choose a PMX model ZIP." }
            return Result(folder.name, pmx, textures, budget.nestedArchives, budget.bytes, firstPmx.absolutePath)
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    private class Budget(var bytes: Long = 0L, var entries: Int = 0, var nestedArchives: Int = 0)

    private fun extract(input: InputStream, root: File, destination: File, depth: Int, budget: Budget) {
        require(depth <= MAX_DEPTH) { "Model package nesting is too deep." }
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry?
            val buffer = ByteArray(16 * 1024)
            while (zip.nextEntry.also { entry = it } != null) {
                val item = entry ?: continue
                budget.entries++
                require(budget.entries <= MAX_ENTRIES) { "Model package contains too many files." }
                val output = File(destination, item.name).canonicalFile
                val rootPath = root.canonicalPath + File.separator
                require(output.path.startsWith(rootPath) || output == root) { "Unsafe archive path rejected." }
                if (item.isDirectory) {
                    output.mkdirs()
                    zip.closeEntry()
                    continue
                }
                output.parentFile?.mkdirs()
                var fileBytes = 0L
                FileOutputStream(output).use { sink ->
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        fileBytes += count
                        budget.bytes += count
                        require(budget.bytes <= MAX_EXPANDED_BYTES) { "Expanded model package exceeds the 600 MB safety limit." }
                        require(fileBytes <= MAX_INPUT_BYTES) { "A model package entry is too large." }
                        sink.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                if (output.extension.equals("zip", true) && depth < MAX_DEPTH) {
                    budget.nestedArchives++
                    FileInputStream(output).use { nested -> extract(nested, root, File(output.parentFile, output.nameWithoutExtension), depth + 1, budget) }
                }
            }
        }
    }
}