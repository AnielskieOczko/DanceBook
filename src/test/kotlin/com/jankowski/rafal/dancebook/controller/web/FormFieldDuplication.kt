package com.jankowski.rafal.dancebook.controller.web

private val formRegex = Regex("""<form\b[^>]*>(.*?)</form>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val elementRegex = Regex("""<(input|select|textarea)\b([^>]*)>""", RegexOption.IGNORE_CASE)
private val nameRegex = Regex("""\bname\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val typeRegex = Regex("""\btype\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val disabledRegex = Regex("""\bdisabled\b""", RegexOption.IGNORE_CASE)
private val valueRegex = Regex("""\bvalue\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

/**
 * Reports every rendered form on [html] that posts the same field name more than once.
 *
 * A duplicated name is how issue #121 corrupted data: the browser submits one value per
 * element, Spring joins the multi-valued parameter with commas, and the saved value grows
 * on every save. Asserting over the rendered HTML keeps that path closed whatever caused
 * the duplication — an ambiguous fragment selector, a copy-pasted input, a new catalog entry.
 *
 * Elements that legitimately share a name are not reported: radio groups, submit and reset
 * buttons that do not post form state, disabled controls that are never submitted, and
 * checkboxes of a multi-select, which share a name but carry distinct values.
 */
internal fun findDuplicateFormFieldErrors(html: String, uri: String): List<String> {
    val errors = mutableListOf<String>()

    for ((formIndex, formMatch) in formRegex.findAll(html).withIndex()) {
        val formContent = formMatch.groupValues[1]
        val fieldNames = mutableListOf<String>()
        val checkboxNamesAndValues = mutableSetOf<Pair<String, String>>()

        for (elMatch in elementRegex.findAll(formContent)) {
            val tag = elMatch.groupValues[1].lowercase()
            val attrs = elMatch.groupValues[2]

            // Disabled elements are not submittable and do not post form state
            if (disabledRegex.containsMatchIn(attrs)) continue

            val type = typeRegex.find(attrs)?.groupValues?.get(1)?.lowercase() ?: if (tag == "input") "text" else ""

            // Submit/button elements do not post form state; radio buttons share name by design
            if (type == "submit" || type == "button" || type == "reset" || type == "radio") continue

            val name = nameRegex.find(attrs)?.groupValues?.get(1)?.trim()
            if (name.isNullOrEmpty() || name == "_csrf") continue

            if (type == "checkbox") {
                val value = valueRegex.find(attrs)?.groupValues?.get(1) ?: ""
                // Multiple checkboxes sharing the same name must have distinct values (multi-select)
                if (!checkboxNamesAndValues.add(name to value)) {
                    fieldNames.add(name)
                }
            } else {
                fieldNames.add(name)
            }
        }

        val duplicates = fieldNames.groupingBy { it }.eachCount().filter { it.value > 1 }
        if (duplicates.isNotEmpty()) {
            errors.add("Form #$formIndex in $uri has duplicate field names: $duplicates")
        }
    }

    return errors
}
