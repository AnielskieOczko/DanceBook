package com.jankowski.rafal.dancebook.service

/** Plain text from the model, made into the markup the rich-text fields store. */
object AssistantText {

    /**
     * One `<div>` per non-blank line, which is what the Trix editor emits and what the
     * rich-text safelist allows. Markup in the text is escaped, so nothing the model writes can
     * become markup. Null when there is nothing to show.
     */
    fun toRichText(plain: String?): String? {
        val html = plain.orEmpty()
            .replace("\r\n", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<div>${escape(it)}</div>" }
        return html.ifEmpty { null }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
