/*
 * Copyright 2019-present Florian Spieß and the Strumbot Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package strumbot

import dev.minn.jda.ktx.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.utils.MarkdownUtil.maskedLink
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.*
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger(StreamWatcher::class.java) as Logger
const val OFFLINE_DELAY = 2L * 60L // 2 minutes
const val HOOK_NAME = "Stream Notifications"

private val ignoredErrors = setOf<Class<*>>(
    SocketException::class.java,              // Issues on socket creation
    SocketTimeoutException::class.java,       // Timeouts
    UnknownHostException::class.java          // DNS errors
)

fun suppressExpected(t: Throwable) = t::class.java !in ignoredErrors

fun startTwitchService(
    twitch: TwitchApi,
    jda: JDA,
    watchedStreams: Map<String, StreamWatcher>
): Job {
    log.info("Listening for stream(s) from {}",
        if (watchedStreams.size == 1)
            watchedStreams.keys.first()
        else
            watchedStreams.keys.toString()
    )

    return jda.repeatUntilShutdown(30.seconds, ZERO) {
        try {
            val streams = twitch.getStreamByLogin(watchedStreams.keys).await() ?: return@repeatUntilShutdown

            // Launch all the watcher updates in parallel
            val jobs = watchedStreams.map { (name, watcher) ->
                val stream = streams.find { it.userLogin.equals(name, true) }
                watcher.handle(stream)
            }

            // Then await them
            jobs.awaitAll()
        } catch (ex: Exception) {
            if (suppressExpected(ex))
                log.error("Error in twitch stream service", ex)
            // Authorization errors should cancel our process
            if (ex is NotAuthorized)
                throw ex
        }
    }
}

data class Timestamps(val display: String, val twitchFormat: String) {
    companion object {
        fun from(timestamp: Int): Timestamps {
            val duration = Duration.ofSeconds(timestamp.toLong())
            val hours = duration.toHours()
            val minutes = duration.minusHours(hours).toMinutes()
            val seconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds()
            return Timestamps(
                "%02d:%02d:%02d".format(hours, minutes, seconds),
                "%02dh%02dm%02ds".format(hours, minutes, seconds))
        }
    }
}

data class StreamElement(val game: Game, val timestamp: Int, var videoId: String) {
    fun toVideoUrl() = "https://www.twitch.tv/videos/${videoId}"

    fun toVodLink(comment: String = game.name): String {
        val (display, twitchFormat) = Timestamps.from(timestamp)
        val url = "${toVideoUrl()}?t=${twitchFormat}"
        return "[`${display}`](${url}) $comment"
    }
}

suspend fun <T> Deferred<T>.getOrNull(comment: String) = try {
    await()
} catch (ex: Exception) {
    log.error("Failed to fetch {}", comment, ex)
    null
}

class StreamWatcher(
    private val twitch: TwitchApi,
    private val jda: JDA,
    private val configuration: Configuration,
    private val userLogin: String,
    private val activityService: ActivityService
) {
    @Volatile private var currentElement: StreamElement? = null
    private var offlineTimestamp = 0L
    private var streamStarted = 0L
    private var currentActivity: Activity? = null
    private var userId: String = ""
    private var language: Locale = Locale.forLanguageTag("en")
    private val timestamps: MutableList<StreamElement> = mutableListOf()
    private val webhook: WebhookClient<*> = configuration.streamNotifications.asWebhook(jda)

    fun handle(stream: Stream?) = jda.scope.async {
        // There are 4 states we can process
        if (currentElement != null) {
            when {
                // 1. The stream was online and is now offline
                // => Send offline notification (vod event)
                stream == null -> handleOffline()

                // 2. The stream was online and has switched the game
                // => Send update game notification (update event)
                stream.gameId != currentElement?.game?.gameId -> {
                    offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                    val video = twitch.getVideoByStream(stream).getOrNull("video")
                    handleUpdate(stream, video?.id ?: "")
                }

                // 3. The stream was online and has not switched the game
                // => Do nothing
                else -> {
                    offlineTimestamp = 0 // We can skip one offline event since we are currently live, and it might hickup
                    if (currentElement?.videoId == "") { // if twitch failed to provide a vod link, try updating it
                        val video = twitch.getVideoByStream(stream).getOrNull("video")

                        video?.id?.let {
                            currentElement?.videoId = it
                        }
                    }
                }
            }
        } else {
            // 4. The stream was offline and has come online
            // => Send go live notification (live event)
            if (stream != null) {
                offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                val game: Game
                val videoId: String
                val thumbnail: InputStream?
                // Run stuff async
                jda.scope.apply {
                    // Launch each getter in parallel
                    val getGame = async { twitch.getGame(stream).getOrNull("game") ?: EMPTY_GAME }
                    val getVod = async { twitch.getVideoByStream(stream).getOrNull("video")?.id ?: "" }
                    val getThumbnail = async { twitch.getThumbnail(stream).getOrNull("thumbnail") }

                    game = getGame.await()
                    videoId = getVod.await()
                    thumbnail = getThumbnail.await()
                }

                handleGoLive(stream, game, videoId, thumbnail)
            }
        }
    }

    private fun updateActivity(newActivity: Activity?) {
        currentActivity?.let(activityService::removeActivity)
        currentActivity = newActivity
        newActivity?.let(activityService::addActivity)
    }

    /// EVENTS

    private suspend fun handleOffline() {
        if (offlineTimestamp == 0L) {
            offlineTimestamp = OffsetDateTime.now().toEpochSecond()
            return
        } else if (OffsetDateTime.now().toEpochSecond() - offlineTimestamp < OFFLINE_DELAY) {
            return
        }

        log.info("Stream from $userLogin went offline!")
        updateActivity(null)
        timestamps.add(currentElement!!)
        currentElement = null
        val timestamps = this.timestamps.toList()
        val firstSegment = timestamps.first()
        this.timestamps.clear()

        val index = timestamps.asSequence()
                              .map { it.toVodLink() }
                              .fold(StringBuilder()) { a, b -> a.append('\n').append(b) }

        // Find most recent video available, the streamer might delete a vod during the stream
        val video = timestamps
            .asReversed()
            .map { it.videoId }
            .filter { it.isNotEmpty() }
            .map { twitch.getVideoById(it).await() }
            .firstOrNull()

        val thumbnail = video?.let { twitch.getThumbnail(it).await() }
        val videoUrl = firstSegment.toVideoUrl()

        val clips = if (configuration.topClips > 0)
            twitch.getTopClips(userId, streamStarted, configuration.topClips).await() ?: emptyList()
        else
            emptyList()

        val embed = makeEmbedBase(video?.title ?: "<Video Removed>", videoUrl).apply {
            appendIndex(index)
            if (clips.isNotEmpty()) field {
                inline = false
                name = getText(language, "offline.clips")
                value = clips.asSequence()
                    .withIndex()
                    .map { (i, it) ->
                        val link = maskedLink(limit(it.title, 25) + " \uD83E\uDC55", it.url)
                        // <index> <Title> - <ViewCount> views
                        "`${i + 1}.` $link \u2022 **${it.views}**\u00A0views"
                    }
                    .joinToString("\n")
            }
        }

        withPing("vod") { mention ->
            val (_, duration) = Timestamps.from((offlineTimestamp - streamStarted).toInt())
            val content = "$mention ${getText(language, "offline.content", "name" to userLogin, "time" to duration)}"
            val message = Message(content = content, embed = embed.build())
            webhook.fireEvent("vod") {
                sendMessage(message).apply {
                    thumbnail?.let { addFile(it, "thumbnail.jpg") }
                }
            }
        }
    }

    private suspend fun handleGoLive(stream: Stream, game: Game, videoId: String, thumbnail: InputStream?) {
        language = getLocale(stream)
        log.info("Stream from {} started with game {} ({})", userLogin, game.name, game.gameId)
        updateActivity(Activity.streaming("$userLogin playing ${game.name}", "https://www.twitch.tv/${userLogin}"))

        streamStarted = stream.startedAt
        currentElement = StreamElement(game, 0, videoId)
        userId = stream.userId

        withPing("live") { mention ->
            val content = "$mention ${getText(language, "live.content",
                "name" to userLogin,
                "game" to "**${game.name}**")
            }"
            val embed = makeEmbed(language, stream, game, userLogin, null)
            val message = Message(content = content, embed = embed)
            webhook.fireEvent("live") {
                sendMessage(message).apply {
                    thumbnail?.let { addFile(it, "thumbnail.jpg") }
                }
            }
        }
    }

    private suspend fun handleUpdate(stream: Stream, videoId: String) {
        timestamps.add(currentElement!!)
        userId = stream.userId
        val game = twitch.getGame(stream).await() ?: return
        log.info("Stream from $userLogin changed game ${currentElement?.game?.name} -> ${game.name}")
        updateActivity(Activity.streaming("$userLogin playing ${game.name}", "https://www.twitch.tv/${userLogin}"))
        val timestamp = System.currentTimeMillis() / 1000 - stream.startedAt
        currentElement = StreamElement(game, timestamp.toInt(), videoId)
        val thumbnail = twitch.getThumbnail(stream).await()

        withPing("update") { mention ->
            val content = "$mention ${getText(language, "update.content",
                "name" to userLogin,
                "game" to "**${game.name}**")
            }"
            val embed = makeEmbed(language, stream, game, userLogin, currentElement)
            val message = Message(content = content, embed = embed)
            webhook.fireEvent("update") {
                sendMessage(message).apply {
                    thumbnail?.let { addFile(thumbnail, "thumbnail.jpg") }
                }
            }
        }
    }

    /// HELPERS

    // Run callback with mentionable role
    private inline fun <T> withPing(type: String, block: (String) -> T): T {
        val roleId = jda.getRoleByType(configuration, type)
        return block("<@&$roleId>")
    }

    // Fire webhook event if enabled in the configuration
    private suspend inline fun <T> WebhookClient<*>.fireEvent(type: String, crossinline block: WebhookClient<*>.() -> RestAction<T>): T? {
        return if (type in configuration.events) {
            block(this).await()
        } else {
            null
        }
    }

    // Add timestamp index for vod (possibly split up into multiple fields)
    private fun InlineEmbed.appendIndex(index: StringBuilder) {
        // Remove leading whitespace (from reduce step)
        if (index[0] == '\n')
            index.deleteCharAt(0)
        if (index.length > 1000) { // we are limited by the capacity of the field
            while (index.isNotEmpty()) {
                // Build chunks of maximum 1000 bytes each
                val chunk = buildString {
                    while (index.isNotEmpty()) {
                        var nextNewLine = index.indexOf('\n')
                        if (nextNewLine <= 0)
                            nextNewLine = index.length

                        val line = index.substring(0, nextNewLine)
                        // Check if line would exceed capacity
                        if (length + line.length >= 1000)
                            break
                        append("\n").append(line)
                        index.delete(0, nextNewLine + 1)
                    }

                    // Trim leading linefeed
                    deleteCharAt(0)
                }

                // Add inline fields (3 per row)
                field {
                    inline = true
                    name = getText(language, "offline.timestamps")
                    value = chunk
                }
            }
        } else {
            field {
                inline = false
                name = getText(language, "offline.timestamps")
                value = index.toString()
            }
        }
    }
}

private fun makeEmbedBase(title: String, url: String) = EmbedBuilder {
    color = 0x6441A4
    image = "attachment://thumbnail.jpg"
    this.title = url
    author(title)
}

private fun makeEmbed(
    language: Locale,
    stream: Stream,
    game: Game,
    twitchName: String,
    currentSegment: StreamElement? = null
) = makeEmbedBase(stream.title, "https://www.twitch.tv/$twitchName").apply {
    field(getText(language, "playing"), game.name)
    field(
        name=getText(language, "started_at"),
        value="<t:${stream.startedAt}:F>"
    )
    if (currentSegment != null) {
        description = "Start watching at ${currentSegment.toVodLink("")}"
    }
}.build()

private fun limit(input: String, limit: Int): String {
    if (input.length <= limit)
        return input
    return input.substring(0, limit) + "\u2026"
}