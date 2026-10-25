package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Material
import java.util.Locale

interface PdfExportService {
    fun exportMaterialPdf(material: Material, locale: Locale): ByteArray
    fun exportDanceFigurePdf(danceFigure: DanceFigure, locale: Locale): ByteArray
    fun renderHtmlToPdf(html: String): ByteArray
}
