package com.teachmint.sharex.share.analytics

import com.teachmint.sharex.share.shared.NetworkConfig
import com.teachmint.sharex.share.shared.ShareXJson
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.Json

interface AnalyticsApi {
    suspend fun postEvent(
        eventId: String,
        eventTag: String,
        payload: AnalyticsEventPayload,
        identity: AnalyticsIdentity,
    ): Result<Unit>
}

class KtorAnalyticsApi(
    private val client: HttpClient,
) : AnalyticsApi {
    private val analyticsJson = Json(ShareXJson) {
        // Optional event-specific fields should be omitted when not provided.
        explicitNulls = false
    }

    override suspend fun postEvent(
        eventId: String,
        eventTag: String,
        payload: AnalyticsEventPayload,
        identity: AnalyticsIdentity,
    ): Result<Unit> {
        val payloadJson = analyticsJson.encodeToString(payload)
        val baseUrls = NetworkConfig.analyticsBaseUrlFallbackChain()
        var lastFailure: Throwable? = null

        for ((index, baseUrl) in baseUrls.withIndex()) {
            val isLastCandidate = index == baseUrls.lastIndex
            val attemptResult = runCatching {
                val response = client.submitForm(
                    url = buildEventUrl(baseUrl, eventId),
                    formParameters = parameters {
                        append("data", payloadJson)
                        append("event_tag", eventTag)
                    },
                ) {
                    headers.append("serialnumber", identity.serialNumber)
                    headers.append("unique-device-id", identity.uniqueDeviceId)
                    headers.append("app-id", identity.appId)
                }
                verifySuccessOrThrow(response, eventId, baseUrl)
            }

            if (attemptResult.isSuccess) {
                if (index > 0) {
                    println(
                        "ANALYTICS: Sent $eventId via fallback base URL $baseUrl",
                    )
                }
                return Result.success(Unit)
            }

            val failure = attemptResult.exceptionOrNull()
                ?: IllegalStateException("Unknown analytics transport failure")
            lastFailure = failure

            val shouldRetry = !isLastCandidate && isRetryableNetworkFailure(failure)
            if (!shouldRetry) {
                break
            }

            println(
                "ANALYTICS: Retrying $eventId on next base URL after failure on $baseUrl: " +
                    summarizeFailure(failure),
            )
        }

        return Result.failure(lastFailure ?: IllegalStateException("Unknown analytics failure"))
    }

    private fun buildEventUrl(baseUrl: String, eventId: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/log/event_u/$eventId"
    }

    private fun verifySuccessOrThrow(
        response: HttpResponse,
        eventId: String,
        baseUrl: String,
    ) {
        if (response.status.isSuccess()) return
        error(
            "Analytics event failed for $eventId on $baseUrl with status ${response.status.value}",
        )
    }

    private fun isRetryableNetworkFailure(error: Throwable): Boolean {
        val details = summarizeFailure(error).lowercase()
        return details.contains("sockettimeoutexception") ||
            details.contains("connectexception") ||
            details.contains("failed to connect") ||
            details.contains("timed out") ||
            details.contains("unknownhost") ||
            details.contains("no route to host") ||
            details.contains("network is unreachable")
    }

    private fun summarizeFailure(error: Throwable): String {
        val current = error.message?.trim().orEmpty()
        val cause = error.cause?.message?.trim().orEmpty()
        return buildString {
            append(error::class.simpleName ?: "UnknownError")
            if (current.isNotBlank()) append(": ").append(current)
            if (cause.isNotBlank() && !current.contains(cause)) {
                append(" | cause: ").append(cause)
            }
        }
    }
}
