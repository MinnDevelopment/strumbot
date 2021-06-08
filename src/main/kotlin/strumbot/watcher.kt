/*
 * Copyright 2019-2020 Florian Spieß and the Strumbot Contributors
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

import club.minnced.jda.reactor.asMono
import dev.minn.jda.ktx.EmbedBuilder
import dev.minn.jda.ktx.InlineEmbed
import dev.minn.jda.ktx.Message
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.utils.MarkdownUtil.maskedLink
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import reactor.util.function.Tuple4
import reactor.util.retry.Retry
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import strumbot.ignoreFailure
import strumbot.component1
import strumbot.component2
import strumbot.component3
import strumbot.component4

private val log = LoggerFactory.getLogger(StreamWatcher::class.java) as Logger
const val OFFLINE_DELAY = 2L * 60L // 2 minutes
const val HOOK_NAME = "Stream Notifications"

private val ignoredErrors = setOf<Class<*>>(
    SocketException::class.java,              // Issues on socket creation
    SocketTimeoutException::class.java,       // Timeouts
    UnknownHostException::class.java          // DNS errors
)

fun suppressExpected(t: Throwable) = !Exceptions.isOverflow(t) && t::class.java !in ignoredErrors

fun startTwitchService(
    twitch: TwitchApi,
    watchedStreams: Map<String, StreamWatcher>,
    poolScheduler: Scheduler
): Flux<*> {
    log.info("Listening for stream(s) from {}",
        if (watchedStreams.size == 1)
            watchedStreams.keys.first()
        else
            watchedStreams.keys.toString()
    )

    return Flux.interval(Duration.ZERO, Duration.ofSeconds(30), poolScheduler)
        .flatMap { twitch.getStreamByLogin(watchedStreams.keys) }
        .flatMapSequential { streams ->
            Flux.merge(watchedStreams.map { entry ->
                val (name, watcher) = entry
                val stream = streams.find { it.userLogin.equals(name, true) }
                watcher.handle(stream)
            })
        }
        .doOnError(::suppressExpected) { log.error("Error in twitch stream service", it) }
        .retryWhen(Retry.indefinitely().filter { it !is Error && it !is HttpException })
        .retryWhen(Retry.backoff(2, Duration.ofSeconds(30)).transientErrors(true))
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

    fun handle(stream: Stream?): Mono<*> {
        // There are 4 states we can process
        return if (currentElement != null) {
            when {
                // 1. The stream was online and is now offline
                // => Send offline notification (vod event)
                stream == null -> handleOffline()

                // 2. The stream was online and has switched the game
                // => Send update game notification (update event)
                stream.gameId != currentElement?.game?.gameId -> {
                    offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                    twitch.getVideoByStream(stream)
                        .ignoreFailure()
                        .map(Video::id).switchIfEmpty("".toMono())
                        .flatMap { handleUpdate(stream, it) }
                }

                // 3. The stream was online and has not switched the game
                // => Do nothing
                else -> {
                    offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                    if (currentElement?.videoId == "") { // if twitch failed to provide a vod link try updating it
                        twitch.getVideoByStream(stream)
                            .map(Video::id)
                            .ignoreFailure()
                            .flatMap {
                                currentElement?.apply { videoId = it }
                                Mono.empty<Message>()
                            }
                    } else Mono.empty()
                }
            }
        } else {
            // 4. The stream was offline and has come online
            // => Send go live notification (live event)
            if (stream != null) {
                offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                val getStream = stream.toMono()
                val getGame = twitch.getGame(stream).ignoreFailure()
                val getVod = twitch.getVideoByStream(stream).ignoreFailure().map(Video::id).switchIfEmpty("".toMono())
                val getThumbnail = twitch.getThumbnail(stream)
                    .ignoreFailure()
                    .map { Optional.of(it) }
                    .switchIfEmpty(Optional.empty<InputStream>().toMono())

                Mono.zip(getStream, getGame, getVod, getThumbnail)
                    .flatMap(this::handleGoLive)
            } else {
                Mono.empty()
            }
        }
    }

    private fun updateActivity(newActivity: Activity?) {
        currentActivity?.let(activityService::removeActivity)
        currentActivity = newActivity
        newActivity?.let(activityService::addActivity)
    }

    /// EVENTS

    private fun handleOffline(): Mono<*> {
        if (offlineTimestamp == 0L) {
            offlineTimestamp = OffsetDateTime.now().toEpochSecond()
            return Mono.empty<Unit>()
        } else if (OffsetDateTime.now().toEpochSecond() - offlineTimestamp < OFFLINE_DELAY) {
            return Mono.empty<Unit>()
        }

        log.info("Stream from $userLogin went offline!")
        updateActivity(null)
        timestamps.add(currentElement!!)
        currentElement = null
        val timestamps = this.timestamps.toList()
        val firstSegment = timestamps.first()
        this.timestamps.clear()

        return mono {
            val index = timestamps.asSequence()
                                  .map { it.toVodLink() }
                                  .fold(StringBuilder()) { a, b -> a.append('\n').append(b) }

            // Find most recent video available, the streamer might delete a vod during the stream
            val video = timestamps
                .asReversed()
                .toFlux()
                .map { it.videoId }
                .filter { it.isNotEmpty() }
                .flatMap { twitch.getVideoById(it) }
                .await()

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
//                        setUsername(HOOK_NAME)
                        thumbnail?.let { addFile(it, "thumbnail.jpg") }
                    }
                }
            }.awaitFirstOrNull()
        }
    }

    private fun handleGoLive(tuple: Tuple4<Stream, Game, String, Optional<InputStream>>): Mono<*> {
        val (stream, game, videoId, thumbnail) = tuple
        language = getLocale(stream)
        log.info("Stream from $userLogin started with game ${game.name} (${game.gameId})")
        updateActivity(Activity.streaming("$userLogin playing ${game.name}", "https://www.twitch.tv/${userLogin}"))
        streamStarted = stream.startedAt.toEpochSecond()
        currentElement = StreamElement(game, 0, videoId)
        userId = stream.userId
        return withPing("live") { mention ->
            val content = "$mention ${getText(language, "live.content",
                "name" to userLogin,
                "game" to "**${game.name}**")
            }"
            val embed = makeEmbed(language, stream, game, userLogin, null)
            val message = Message(content = content, embed = embed)
            webhook.fireEvent("live") {
                sendMessage(message).apply {
//                    setUsername(HOOK_NAME)
                    thumbnail.ifPresent { addFile(it, "thumbnail.jpg") }
                }
            }
        }
    }

    private fun handleUpdate(stream: Stream, videoId: String): Mono<*> {
        timestamps.add(currentElement!!)
        userId = stream.userId
        return mono {
            val game = twitch.getGame(stream).await() ?: return@mono null
            log.info("Stream from $userLogin changed game ${currentElement?.game?.name} -> ${game.name}")
            updateActivity(Activity.streaming("$userLogin playing ${game.name}", "https://www.twitch.tv/${userLogin}"))
            val timestamp = stream.startedAt.until(OffsetDateTime.now(), ChronoUnit.SECONDS).toInt()
            currentElement = StreamElement(game, timestamp, videoId)
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
//                        setUsername(HOOK_NAME)
                        thumbnail?.let { addFile(thumbnail, "thumbnail.jpg") }
                    }
                }
            }.awaitFirstOrNull()
        }
    }

    /// HELPERS

    // Run callback with mentionable role
    private inline fun <T> withPing(type: String, block: (String) -> Mono<T>): Mono<T> {
        val roleId = jda.getRoleByType(configuration, type)
        return block("<@&$roleId>")
    }

    // Fire webhook event if enabled in the configuration
    private inline fun <T> WebhookClient<*>.fireEvent(type: String, crossinline block: WebhookClient<*>.() -> RestAction<T>): Mono<T> {
        return if (type in configuration.events) {
            block(this).asMono()
        } else {
            Mono.empty()
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
        value=stream.startedAt.format(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(language))
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