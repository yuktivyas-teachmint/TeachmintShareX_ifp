package com.teachmint.sharex.share.shared

private const val PROD_TEACHMINT_BASE_URL = "https://app.teachmint.com/"

enum class TeachmintEnvironment(val baseUrl: String) {
    DEV(PROD_TEACHMINT_BASE_URL),
    QA(PROD_TEACHMINT_BASE_URL),
    PROD(PROD_TEACHMINT_BASE_URL),
}

object NetworkConfig {
    /**
     * Defaults to DEV. The app can switch this at runtime if needed.
     */
    var environment: TeachmintEnvironment = TeachmintEnvironment.DEV

    val teachmintBaseUrl: String
        get() = environment.baseUrl

    /**
     * Analytics events must always go to PROD regardless of environment.
     */
    fun analyticsBaseUrlFallbackChain(): List<String> = listOf(PROD_TEACHMINT_BASE_URL)
}
