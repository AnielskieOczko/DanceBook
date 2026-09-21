package com.jankowski.rafal.dancebook.service

interface RichTextService {

    /**
     * Sanitises long-form user input on write.
     *
     * Permitted markup is restricted to standard block structure (paragraphs, line breaks),
     * lists (ordered, unordered, items), inline emphasis (bold, italic), and links with safe
     * schemes (http, https, mailto). Outbound links automatically receive rel="noopener noreferrer nofollow".
     *
     * If the input is structurally empty (e.g. only whitespace, empty tags, or stripped tags),
     * this returns null so the value persists as absent.
     */
    fun clean(raw: String?): String?

    /**
     * Sanitises and formats long-form content on read for safe unescaped rendering.
     *
     * If [raw] contains no HTML tags (legacy plain-text records), HTML special characters are escaped
     * and line breaks are converted to `<br>` tags to preserve formatting without database migration.
     * If [raw] contains HTML tags, it is re-sanitised against the allowed safelist.
     * Returns null if structurally empty.
     */
    fun render(raw: String?): String?

    /**
     * Converts rich text or plain text into clean, readable plain text.
     *
     * Strips all tags while preserving logical spacing (paragraphs, list items, line breaks)
     * and decoding HTML entities. Used for Google Calendar descriptions and excerpt generation.
     */
    fun toPlainText(raw: String?): String?

    /**
     * Produces a plain-text excerpt up to [max] characters.
     *
     * Guaranteed never to emit markup. Truncation occurs on plain-text character count
     * rather than HTML bytes.
     */
    fun excerpt(raw: String?, max: Int = 160): String?

    /**
     * Returns the length of the visible text typed by the user (ignoring markup bytes).
     */
    fun userTextLength(raw: String?): Int
}
