package com.jankowski.rafal.dancebook.service

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Service
import org.springframework.web.util.HtmlUtils

@Service("richTextService")
class RichTextServiceImpl : RichTextService {

    private val safelist: Safelist = Safelist.none()
        .addTags("p", "div", "br", "strong", "b", "em", "i", "ul", "ol", "li", "a")
        .addAttributes("a", "href")
        .addProtocols("a", "href", "http", "https", "mailto")

    override fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (toPlainText(raw) == null) return null

        if (!containsHtmlTags(raw)) {
            return raw.trim()
        }

        val cleanedHtml = Jsoup.clean(raw, "", safelist, Document.OutputSettings().prettyPrint(false))
        val doc = Jsoup.parseBodyFragment(cleanedHtml)
        doc.outputSettings().prettyPrint(false)
        enforceLinkSecurity(doc)

        val resultHtml = doc.body().html().trim()
        if (toPlainText(resultHtml) == null) return null
        return resultHtml
    }

    override fun render(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (toPlainText(raw) == null) return null

        if (!containsHtmlTags(raw)) {
            val escaped = HtmlUtils.htmlEscape(raw.trim())
            return escaped.replace("\r\n", "<br>").replace("\n", "<br>")
        }

        return clean(raw)
    }

    override fun toPlainText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val doc = Jsoup.parseBodyFragment(raw)
        doc.select("script, style").remove()
        doc.select("br").append("\n")
        doc.select("p, div").prepend("\n\n")
        for (li in doc.select("li")) {
            if (li.hasText()) {
                li.prepend("\n• ")
            }
        }
        val text = doc.body().wholeText()
            .replace('\u00A0', ' ')
            .replace(Regex("""\r\n|\r"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return text.ifBlank { null }
    }

    override fun excerpt(raw: String?, max: Int): String? {
        val plain = toPlainText(raw) ?: return null
        val singleLine = plain.replace(Regex("""\s+"""), " ").trim()
        return if (singleLine.length > max) {
            singleLine.substring(0, max) + "..."
        } else {
            singleLine
        }
    }

    override fun userTextLength(raw: String?): Int =
        toPlainText(raw)?.length ?: 0

    private fun containsHtmlTags(input: String): Boolean {
        if (!input.contains('<')) return false
        val doc = Jsoup.parseBodyFragment(input)
        return doc.body().children().isNotEmpty()
    }

    private fun enforceLinkSecurity(doc: Document) {
        for (a in doc.select("a")) {
            val href = a.attr("href").trim()
            if (href.isBlank()) {
                a.unwrap()
                continue
            }
            if (href.startsWith("http://", ignoreCase = true) ||
                href.startsWith("https://", ignoreCase = true) ||
                href.startsWith("//")
            ) {
                a.attr("rel", "noopener noreferrer nofollow")
            }
        }
    }
}
