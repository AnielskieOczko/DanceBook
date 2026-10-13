package com.jankowski.rafal.dancebook.controller.web

/**
 * The one rule for accepting a `redirect` request parameter. Controllers must not build a
 * `redirect:` from request input without going through it, or an open redirect slips in
 * (`//evil.example` and `/\evil.example` both look like paths to a naive `startsWith("/")`).
 */
object SafeRedirect {

    /**
     * Returns [path] if it is a plain same-site path: a single leading `/`, a second character
     * that is not `/` or `\`, no backslash anywhere and no ASCII control characters
     * (below 0x20, or DEL). Otherwise null, so the caller can fall back to its own default.
     */
    fun sanitize(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        if (!path.startsWith("/")) return null
        if (path.length > 1 && (path[1] == '/' || path[1] == '\\')) return null
        if (path.contains('\\')) return null
        if (path.any { it < ' ' || it.code == 0x7F }) return null
        return path
    }

    /** [sanitize], or [default] when the target is not acceptable. */
    fun orDefault(path: String?, default: String): String = sanitize(path) ?: default
}
