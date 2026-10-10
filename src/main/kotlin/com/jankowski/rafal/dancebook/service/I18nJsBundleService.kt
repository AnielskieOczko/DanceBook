package com.jankowski.rafal.dancebook.service

import java.util.Locale

interface I18nJsBundleService {
    fun getBundleJson(locale: Locale): String
    fun getBundleMap(locale: Locale): Map<String, String>
}
