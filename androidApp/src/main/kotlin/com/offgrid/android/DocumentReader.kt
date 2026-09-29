package com.offgrid.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class DocumentReader(private val context: Context) {
    fun read(uri: Uri): Pair<String, String> {
        require(uri.scheme == "content") { "Choose a document using the file picker." }
        val resolver = context.contentResolver
        val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else "Document"
        } ?: "Document"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytesLimited(12 * 1024 * 1024) } ?: error("Cannot open document.")
        return title to extract(title, resolver.getType(uri).orEmpty(), bytes)
    }
    internal fun extract(title: String, mimeType: String, bytes: ByteArray): String {
        val text = if (mimeType == "application/pdf" || title.endsWith(".pdf", true)) {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(bytes).use { pdf ->
                require(!pdf.isEncrypted) { "Password-protected PDFs are not supported." }
                require(pdf.numberOfPages <= 200) { "Please choose a PDF with at most 200 pages." }
                buildString {
                    val stripper = PDFTextStripper()
                    for (page in 1..pdf.numberOfPages) {
                        stripper.startPage = page; stripper.endPage = page
                        append("\n[Page $page]\n"); append(stripper.getText(pdf))
                        require(length <= 500_000) { "PDF text is too large. Import a shorter document." }
                    }
                }.also { require(it.replace(Regex("\\[Page \\d+\\]"), "").isNotBlank()) { "This PDF has no readable text. Scanned documents are not supported." } }
            }
        } else {
            require(mimeType.startsWith("text/") || title.endsWith(".md", true) || title.endsWith(".txt", true)) { "Choose a text file, Markdown, or text PDF." }
            bytes.toString(Charsets.UTF_8)
        }
        require(text.isNotBlank() && text.length <= 500_000) { "Choose a nonempty document with at most 500,000 characters." }
        return text
    }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while(true) { val n = read(buffer); if(n < 0) break; require(output.size() + n <= limit) { "File exceeds ${limit / 1024 / 1024} MB limit." }; output.write(buffer, 0, n) }
    return output.toByteArray()
}
