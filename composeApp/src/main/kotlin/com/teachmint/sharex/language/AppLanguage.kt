package com.teachmint.sharex.language

/**
 * Languages the host app can be displayed in. Mirrors the set supported by the
 * whiteboard app's language popup (TemporaryLanguage), so both apps offer the
 * same choices on a device.
 */
enum class AppLanguage(
    val lang: String,
    val nativeLang: String,
    val langId: String,
    val countryCode: String,
) {
    ENGLISH("English", "English", "en", ""),
    HINDI("Hindi", "हिंदी", "hi", "in"),
    KANNADA("Kannada", "ಕನ್ನಡ", "kn", "in"),
    ARABIC("Arabic", "عربي", "ar", "ae"),
    NEPALI("Nepali", "नेपाली", "ne", "np"),
    THAI("Thai", "ไทย", "th", "th"),
    VIETNAMESE("Vietnamese", "Tiếng Việt", "vi", "vn"),
    INDONESIAN("Indonesian", "Bahasa Indonesia", "id", "id"),
    SINHALA("Sinhala", "සිංහල", "si", "lk"),
    DARI("Dari", "دری", "fa", "af"),
    BANGLA("Bangla", "বাংলা", "bn", "in"),
    ;

    companion object {
        fun fromLangId(langId: String?): AppLanguage =
            entries.firstOrNull { it.langId == langId } ?: ENGLISH
    }
}
