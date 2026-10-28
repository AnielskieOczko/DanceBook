package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Material
import java.util.Locale

/** One "label: value" line on the cover of a list export; [labelKey] is a message key, [value] is already text. */
data class PdfFilterLine(val labelKey: String, val value: String)

/**
 * What the cover of a list export says about itself. [part] is 1-based; a cover only mentions parts when
 * [totalParts] is more than one. [totalItems] counts every match across all parts.
 */
data class PdfListCover(
    val ownerName: String,
    val filters: List<PdfFilterLine>,
    val part: Int = 1,
    val totalParts: Int = 1,
    val totalItems: Long = 0
)

interface PdfExportService {
    companion object {
        /** A list export never holds more items than this; a longer list is split into parts. */
        const val MAX_ITEMS_PER_PDF = 100

        /** How many parts a list of [totalItems] matches is split into (at least one). */
        fun partCount(totalItems: Long): Int =
            maxOf(1, ((totalItems + MAX_ITEMS_PER_PDF - 1) / MAX_ITEMS_PER_PDF).toInt())
    }

    fun exportMaterialPdf(material: Material, locale: Locale): ByteArray
    fun exportDanceFigurePdf(danceFigure: DanceFigure, locale: Locale): ByteArray

    /** One PDF for [materials] (at most [MAX_ITEMS_PER_PDF]): cover, table of contents, one section per note. */
    fun exportMaterialListPdf(materials: List<Material>, cover: PdfListCover, locale: Locale): ByteArray

    /** One PDF for [figures] (at most [MAX_ITEMS_PER_PDF]): cover, table of contents, one section per figure. */
    fun exportDanceFigureListPdf(figures: List<DanceFigure>, cover: PdfListCover, locale: Locale): ByteArray

    fun renderHtmlToPdf(html: String): ByteArray
}
