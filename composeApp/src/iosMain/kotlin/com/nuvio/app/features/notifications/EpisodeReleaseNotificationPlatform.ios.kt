package com.nuvio.app.features.notifications

import com.nuvio.app.core.storage.ProfileScopedKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import platform.Foundation.NSCalendar
import platform.Foundation.NSDateComponents
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

// 2026-08-27: rewritten to deliver episode-release notifications via ntfy
// (docs.ntfy.sh) instead of UNUserNotificationCenter local scheduling.
//
// Why: local notifications scheduled with UNCalendarNotificationTrigger
// need the OS to wake this exact app's process at the trigger date - that
// depends on iOS treating this as a real, independently-signed app.
// Running under LiveContainer breaks that: LiveContainer hosts multiple
// "guest" apps inside one shared container/process, so iOS's scheduled
// local-notification delivery for a guest app is unreliable (confirmed
// as a known LiveContainer limitation, not something fixable app-side).
//
// ntfy sidesteps the problem entirely: instead of asking iOS to wake this
// process later, we ask ntfy's server to hold the message (via the
// `X-Delay` header) and push it through ntfy's own separately-installed,
// genuinely-signed app at the right time. Scheduling now just means
// "make an HTTP call now", which works identically whether this app is
// running under LiveContainer, SideStore, or a real signed install.
//
// Trade-off, stated plainly: this requires the ntfy app installed and
// subscribed to NtfyConfig.TOPIC. If NtfyConfig.TOPIC is unset (not
// configured at build time), this whole feature silently no-ops rather
// than erroring - same posture as Trakt/Simkl being optional.
internal actual object EpisodeReleaseNotificationPlatform {
    private const val scheduledIdsKey = "episode_release_notification_scheduled_ids"

    private val httpClient = HttpClient(Darwin) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    private fun isConfigured(): Boolean = NtfyConfig.TOPIC.isNotBlank()

    // No OS permission dialog needed for ntfy - delivery happens through
    // the separate ntfy app, which manages its own notification
    // permission independently of this app. "Authorized" here just means
    // "a topic is configured at all".
    actual suspend fun notificationsAuthorized(): Boolean = isConfigured()

    actual suspend fun requestAuthorization(): Boolean = isConfigured()

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        clearScheduledEpisodeReleaseNotifications()
        if (!isConfigured()) return

        val scheduledIds = mutableListOf<String>()

        requests.forEach { request ->
            val dateComponents = buildDateComponents(request.releaseDateIso) ?: return@forEach
            val scheduledDate = NSCalendar.currentCalendar.dateFromComponents(dateComponents) ?: return@forEach
            val delaySeconds = scheduledDate.timeIntervalSince1970.toLong()
            if (delaySeconds <= nowEpochSeconds()) return@forEach

            val published = publish(request, delayEpochSeconds = delaySeconds)
            if (published) scheduledIds += request.requestId
        }

        NSUserDefaults.standardUserDefaults.setObject(
            scheduledIds.joinToString(separator = "|"),
            forKey = ProfileScopedKey.of(scheduledIdsKey),
        )
    }

    // ntfy lets a scheduled (delayed) message be canceled before delivery
    // by DELETEing the same "<topic>/<requestId>" URL it was published to
    // (docs.ntfy.sh/publish - custom message ids) - this only works
    // because we always publish with our own requestId as the path
    // segment rather than letting ntfy generate one.
    actual suspend fun clearScheduledEpisodeReleaseNotifications() {
        if (isConfigured()) {
            trackedScheduledIds().forEach { requestId ->
                runCatching {
                    httpClient.delete("${NtfyConfig.SERVER}/${NtfyConfig.TOPIC}/$requestId")
                }
            }
        }
        NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(scheduledIdsKey))
    }

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {
        if (!isConfigured()) return
        publish(request, delayEpochSeconds = null)
    }

    private suspend fun publish(request: EpisodeReleaseNotificationRequest, delayEpochSeconds: Long?): Boolean =
        runCatching {
            val response = httpClient.post("${NtfyConfig.SERVER}/${NtfyConfig.TOPIC}/${request.requestId}") {
                headers {
                    append("X-Title", request.notificationTitle)
                    append("X-Click", request.deepLinkUrl)
                    request.backdropUrl?.trim()?.takeUnless { it.isEmpty() }?.let { append("X-Attach", it) }
                    delayEpochSeconds?.let { append("X-Delay", it.toString()) }
                }
                setBody(request.notificationBody)
            }
            response.status == HttpStatusCode.OK
        }.getOrDefault(false)

    private fun trackedScheduledIds(): List<String> =
        NSUserDefaults.standardUserDefaults
            .stringForKey(ProfileScopedKey.of(scheduledIdsKey))
            ?.split('|')
            ?.filter { value -> value.isNotBlank() }
            .orEmpty()

    private fun nowEpochSeconds(): Long =
        (platform.Foundation.NSDate().timeIntervalSince1970).toLong()

    private fun buildDateComponents(releaseDateIso: String): NSDateComponents? {
        val parts = releaseDateIso.split('-')
        if (parts.size != 3) return null

        val year = parts[0].toLongOrNull() ?: return null
        val month = parts[1].toLongOrNull() ?: return null
        val day = parts[2].toLongOrNull() ?: return null

        return NSDateComponents().apply {
            this.year = year
            this.month = month
            this.day = day
            this.hour = EpisodeReleaseNotificationHour.toLong()
            this.minute = EpisodeReleaseNotificationMinute.toLong()
            this.second = 0
            this.calendar = NSCalendar.currentCalendar
            setTimeZone(NSCalendar.currentCalendar.timeZone)
        }
    }
}
