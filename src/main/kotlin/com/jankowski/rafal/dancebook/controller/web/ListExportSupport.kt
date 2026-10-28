package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.PdfExportService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.util.UriComponentsBuilder
import java.nio.charset.StandardCharsets

/** One downloadable part of a list export: items [from]..[to] of the matching list, 1-based and inclusive. */
data class ExportPart(val number: Int, val from: Long, val to: Long, val url: String)

/**
 * What the Notes and Figures list exports (#416) have in common: how a long list is cut into parts, how the
 * current filters travel in a URL, and the download response. The two controllers differ only in their filters.
 */
object ListExportSupport {

    /** The URL of part [part] of the export at [path], carrying the same filters as the list that started it. */
    fun pdfUrl(path: String, filters: List<Pair<String, String>>, part: Int): String {
        val builder = UriComponentsBuilder.fromPath("$path/pdf")
        filters.forEach { (name, value) -> builder.queryParam(name, value) }
        builder.queryParam("part", part)
        return builder.build().encode().toUriString()
    }

    /** Every part of an export of [total] matches, each at most [PdfExportService.MAX_ITEMS_PER_PDF] items. */
    fun parts(path: String, filters: List<Pair<String, String>>, total: Long): List<ExportPart> {
        val max = PdfExportService.MAX_ITEMS_PER_PDF.toLong()
        return (1..PdfExportService.partCount(total)).map { number ->
            val from = (number - 1) * max + 1
            ExportPart(number, from, minOf(total, number * max), pdfUrl(path, filters, number))
        }
    }

    /** A PDF named `dancebook-notes.pdf`, or `dancebook-notes-part-2-of-3.pdf` when the export has several parts. */
    fun download(bytes: ByteArray, baseName: String, part: Int, totalParts: Int): ResponseEntity<ByteArray> {
        val filename = if (totalParts > 1) "$baseName-part-$part-of-$totalParts.pdf" else "$baseName.pdf"
        val contentDisposition = ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)
            .build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes)
    }
}
