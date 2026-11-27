// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.settings

import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * The console's language and country, as the Xbox 360 numbers them (Xenia's
 * user_language and user_country cvars), taken from the device's own
 * languages. A game that lacks the language falls back to its own default,
 * as on the console.
 */
object ConsoleLanguage {
    /** Every language a console can be set to, with its own name. */
    val LANGUAGES: List<Pair<Int, String>> = listOf(
        1 to "English", 2 to "日本語", 3 to "Deutsch", 4 to "Français", 5 to "Español",
        6 to "Italiano", 7 to "한국어", 8 to "繁體中文", 9 to "Português", 11 to "Polski",
        12 to "Русский", 13 to "Svenska", 14 to "Türkçe", 15 to "Norsk", 16 to "Nederlands",
        17 to "简体中文",
    )

    fun name(id: Int): String = LANGUAGES.firstOrNull { it.first == id }?.second ?: id.toString()

    /**
     * The languages in order of preference: Kiwi's own if the user chose one for it
     * in Android's settings (Android 13+), else the system's.
     */
    private fun appLocales(): List<Locale> = LocaleList.getDefault().toList()

    /** The system's languages, for the region: a language chosen for the app has none. */
    private fun systemLocales(): List<Locale> = Resources.getSystem().configuration.locales.toList()

    private fun LocaleList.toList(): List<Locale> = (0 until size()).map { get(it) }.ifEmpty { listOf(Locale.getDefault()) }

    /** The first of the user's languages a console has, else English. */
    fun fromDevice(): Int = appLocales().firstNotNullOfOrNull { fromLocale(it) } ?: ENGLISH

    /** The console country of the device's region, or null to keep Xenia's default (US). */
    fun deviceCountry(): Int? = systemLocales().firstNotNullOfOrNull { COUNTRIES[it.country.uppercase()] }

    fun fromLocale(locale: Locale): Int? = when (locale.language) {
        "en" -> 1
        "ja" -> 2
        "de" -> 3
        "fr" -> 4
        "es" -> 5
        "it" -> 6
        "ko" -> 7
        "zh" -> if (isTraditionalChinese(locale)) 8 else 17
        "pt" -> 9
        "pl" -> 11
        "ru" -> 12
        "sv" -> 13
        "tr" -> 14
        "nb", "no", "nn" -> 15
        "nl" -> 16
        else -> null
    }

    private fun isTraditionalChinese(locale: Locale): Boolean =
        locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))

    private const val ENGLISH = 1

    /** ISO 3166 code -> console country id, as listed by the user_country cvar. */
    private val COUNTRIES: Map<String, Int> = mapOf(
        "AE" to 1, "AL" to 2, "AM" to 3, "AR" to 4, "AT" to 5, "AU" to 6, "AZ" to 7, "BE" to 8,
        "BG" to 9, "BH" to 10, "BN" to 11, "BO" to 12, "BR" to 13, "BY" to 14, "BZ" to 15,
        "CA" to 16, "CH" to 18, "CL" to 19, "CN" to 20, "CO" to 21, "CR" to 22, "CZ" to 23,
        "DE" to 24, "DK" to 25, "DO" to 26, "DZ" to 27, "EC" to 28, "EE" to 29, "EG" to 30,
        "ES" to 31, "FI" to 32, "FO" to 33, "FR" to 34, "GB" to 35, "GE" to 36, "GR" to 37,
        "GT" to 38, "HK" to 39, "HN" to 40, "HR" to 41, "HU" to 42, "ID" to 43, "IE" to 44,
        "IL" to 45, "IN" to 46, "IQ" to 47, "IR" to 48, "IS" to 49, "IT" to 50, "JM" to 51,
        "JO" to 52, "JP" to 53, "KE" to 54, "KG" to 55, "KR" to 56, "KW" to 57, "KZ" to 58,
        "LB" to 59, "LI" to 60, "LT" to 61, "LU" to 62, "LV" to 63, "LY" to 64, "MA" to 65,
        "MC" to 66, "MK" to 67, "MN" to 68, "MO" to 69, "MV" to 70, "MX" to 71, "MY" to 72,
        "NI" to 73, "NL" to 74, "NO" to 75, "NZ" to 76, "OM" to 77, "PA" to 78, "PE" to 79,
        "PH" to 80, "PK" to 81, "PL" to 82, "PR" to 83, "PT" to 84, "PY" to 85, "QA" to 86,
        "RO" to 87, "RU" to 88, "SA" to 89, "SE" to 90, "SG" to 91, "SI" to 92, "SK" to 93,
        "SV" to 95, "SY" to 96, "TH" to 97, "TN" to 98, "TR" to 99, "TT" to 100, "TW" to 101,
        "UA" to 102, "US" to 103, "UY" to 104, "UZ" to 105, "VE" to 106, "VN" to 107, "YE" to 108,
        "ZA" to 109,
    )
}
