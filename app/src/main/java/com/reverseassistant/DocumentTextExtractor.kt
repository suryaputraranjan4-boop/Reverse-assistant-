package com.reverseassistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Extracts bounded text from explicitly selected documents. Scanned PDFs need OCR and are not handled. */
object DocumentTextExtractor {
    private const val MAX_CHARS = 120_000
    private const val MAX_BYTES = 1_500_000
    private const val MAX_PDF_BYTES = 20_000_000L

    fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.take(80)
            ?.ifBlank { null } ?: "Attached document"

    fun extract(context: Context, uri: Uri): String {
        val name = displayName(uri).lowercase()
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        return when {
            mime == "application/pdf" || name.endsWith(".pdf") -> extractPdf(context, uri)
            mime.contains("wordprocessingml") || name.endsWith(".docx") -> extractDocx(context, uri)
            else -> extractPlainText(context, uri)
        }.take(MAX_CHARS)
    }

    private fun extractPlainText(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open this document.")
        val output = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0 || total >= MAX_BYTES) break
                val allowed = minOf(count, MAX_BYTES - total)
                output.write(buffer, 0, allowed)
                total += allowed
            }
        }
        return output.toString("UTF-8")
    }

    private fun extractPdf(context: Context, uri: Uri): String {
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        require(size == null || size <= MAX_PDF_BYTES) { "PDF is larger than the 20 MB import limit." }
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open this PDF.")
        input.use { stream ->
            PDDocument.load(stream).use { document ->
                val stripper = PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = minOf(document.numberOfPages, 30)
                return stripper.getText(document).take(MAX_CHARS)
            }
        }
    }

    private fun extractDocx(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open this DOCX file.")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val parser = XmlPullParserFactory.newInstance().newPullParser()
                        parser.setInput(zip, "UTF-8")
                        val output = StringBuilder()
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT && output.length < MAX_CHARS) {
                            when (event) {
                                XmlPullParser.START_TAG -> when (parser.name) {
                                    "p" -> if (output.isNotEmpty()) output.append('\n')
                                    "tab" -> output.append('\t')
                                    "br" -> output.append('\n')
                                }
                                XmlPullParser.TEXT -> output.append(parser.text)
                            }
                            event = parser.next()
                        }
                        return output.toString().take(MAX_CHARS)
                    }
                    zip.closeEntry()
                }
            }
        }
        error("This DOCX file has no readable main document text.")
    }
}
