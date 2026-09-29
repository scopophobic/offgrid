package com.offgrid.android

import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DocumentReaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun extractsTextPdfLocallyWithPageReferences() {
        PDFBoxResourceLoader.init(context)
        val bytes = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(40f, 700f); stream.showText("Kyoto station travel guide"); stream.endText()
            }
            doc.save(bytes)
        }
        val text = DocumentReader(context).extract("guide.pdf", "application/pdf", bytes.toByteArray())
        assertTrue(text.contains("Kyoto station travel guide")); assertTrue(text.contains("[Page 1]"))
    }
    @Test fun rejectsImageOnlyPdfAndReadsText() {
        PDFBoxResourceLoader.init(context)
        val bytes = ByteArrayOutputStream()
        PDDocument().use { doc -> doc.addPage(PDPage()); doc.save(bytes) }
        assertTrue(runCatching { DocumentReader(context).extract("scan.pdf", "application/pdf", bytes.toByteArray()) }.isFailure)
        assertEquals("Travel notes", DocumentReader(context).extract("notes.txt", "text/plain", "Travel notes".toByteArray()))
    }
}
