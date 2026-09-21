package com.jankowski.rafal.dancebook.dto

data class FormSelectOption(
    val value: String,
    val label: String
) {
    val displayName: String get() = label
}
