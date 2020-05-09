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

import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.receive.ReadonlyMessage
import club.minnced.discord.webhook.send.AllowedMentions
import club.minnced.discord.webhook.send.WebhookEmbed.*
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Activity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.toFlux
import reactor.core.publisher.toMono
import reactor.core.scheduler.Scheduler
import reactor.util.function.Tuple4
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

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

    return Flux.interval(Duration.ZERO, Duration.ofSeconds(10), poolScheduler)
        .flatMap { twitch.getStreamByLogin(watchedStreams.keys) }
        .flatMapSequential { streams ->
            Flux.merge(watchedStreams.map { entry ->
                val (name, watcher) = entry
                val stream = streams.find { it.userLogin.equals(name, true) }
                watcher.handle(stream)
            })
        }
        .doOnError(::suppressExpected) { log.error("Error in twitch stream service", it) }
        .retry { it !is Error && it !is HttpException }
        .retryBackoff(2, Duration.ofSeconds(10))
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
    private val pool: ScheduledExecutorService,
    private val activityService: ActivityService
) {

    @Volatile private var currentElement: StreamElement? = null
    private var offlineTimestamp = 0L
    private var streamStarted = 0L
    private var currentActivity: Activity? = null
    private val timestamps: MutableList<StreamElement> = mutableListOf()
    private val webhook: WebhookClient by lazy {
        WebhookClientBuilder(configuration.streamNotifications)
            .setExecutorService(pool)
            .setHttpClient(jda.httpClient)
            .setWait(true)
            .build()
    }

    fun handle(stream: Stream?): Mono<ReadonlyMessage> {
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
                            .flatMap {
                                currentElement?.apply { videoId = it }
                                Mono.empty<ReadonlyMessage>()
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
                val getGame = twitch.getGame(stream)
                val getVod = twitch.getVideoByStream(stream).map(Video::id).switchIfEmpty("".toMono())
                val getThumbnail = twitch.getThumbnail(stream)
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

    private fun handleOffline(): Mono<ReadonlyMessage> {
        if (offlineTimestamp == 0L) {
            offlineTimestamp = OffsetDateTime.now().toEpochSecond()
            return Mono.empty()
        } else if (OffsetDateTime.now().toEpochSecond() - offlineTimestamp < OFFLINE_DELAY) {
            return Mono.empty()
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
                .flatMap { twitch.getVideoById(it) }
                .awaitFirstOrNull()

            val thumbnail = video?.let { twitch.getThumbnail(it).awaitFirstOrNull() }
            val videoUrl = firstSegment.toVideoUrl()
            val embed = makeEmbedBase(video?.title ?: "<Video Removed>", videoUrl)
            appendIndex(index, embed)

            withPing("vod") { mention ->
                val (_, duration) = Timestamps.from((offlineTimestamp - streamStarted).toInt())
                val message = WebhookMessageBuilder()
                    .setContent("$mention VOD [$duration]")
                    .setUsername(HOOK_NAME)
                    .addEmbeds(embed.build())
                    .apply {
                        if (thumbnail != null)
                            addFile("thumbnail.jpg", thumbnail)
                    }
                    .build()

                webhook.fireEvent("vod") { send(message) }
            }.awaitFirst()
        }
    }

    private fun handleGoLive(tuple: Tuple4<Stream, Game, String, Optional<InputStream>>): Mono<ReadonlyMessage> {
        val (stream, game, videoId, thumbnail) = tuple
        log.info("Stream from $userLogin started with game ${game.name} (${game.gameId})")
        updateActivity(Activity.streaming("$userLogin playing ${game.name}", "https://www.twitch.tv/${userLogin}"))
        streamStarted = stream.startedAt.toEpochSecond()
        currentElement = StreamElement(game, 0, videoId)
        return withPing("live") { mention ->
            val embed = makeEmbed(stream, game, thumbnail.orElse(null), userLogin)
                .setContent("$mention $userLogin is live with **${game.name}**!")
                .setUsername(HOOK_NAME)
                .build()
            webhook.fireEvent("live") { send(embed) }
        }
    }

    private fun handleUpdate(stream: Stream, videoId: String): Mono<ReadonlyMessage> {
        timestamps.add(currentElement!!)
        return mono {
            val game = twitch.getGame(stream).awaitFirst()
            log.info("Stream from $userLogin changed game ${currentElement?.game?.name} -> ${game.name}")
            updateActivity(Activity.streaming("$userLogin playing ${game.name}", "https://www.twitch.tv/${userLogin}"))
            val timestamp = stream.startedAt.until(OffsetDateTime.now(), ChronoUnit.SECONDS).toInt()
            currentElement = StreamElement(game, timestamp, videoId)
            val thumbnail = twitch.getThumbnail(stream).awaitFirstOrNull()

            withPing("update") { mention ->
                val embed = makeEmbed(stream, game, thumbnail, userLogin, currentElement)
                    .setContent("$mention $userLogin switched game to **${game.name}**!")
                    .setUsername(HOOK_NAME)
                    .build()
                webhook.fireEvent("update") { send(embed) }
            }.awaitFirst()
        }
    }

    /// HELPERS

    // Run callback with mentionable role
    private inline fun <T> withPing(type: String, block: (String) -> Mono<T>): Mono<T> {
        val roleId = jda.getRoleByType(configuration, type)
        return block("<@&$roleId>")
    }

    // Fire webhook event if enabled in the configuration
    private inline fun <T> WebhookClient.fireEvent(type: String, crossinline block: WebhookClient.() -> CompletableFuture<T>): Mono<T> {
        return if (type in configuration.events) {
            Mono.fromFuture { block(this) }
        } else {
            Mono.empty()
        }
    }

    // Add timestamp index for vod (possibly split up into multiple fields)
    private fun appendIndex(index: StringBuilder,embed: WebhookEmbedBuilder) {
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
                embed.addField(EmbedField(true, "Time Stamps", chunk))
            }
        } else {
            embed.addField(EmbedField(false, "Time Stamps", index.toString()))
        }
    }
}

private fun makeEmbedBase(title: String, url: String): WebhookEmbedBuilder {
    val embed = WebhookEmbedBuilder()
    embed.setColor(0x6441A4)
    embed.setImageUrl("attachment://thumbnail.jpg")
    embed.setTitle(EmbedTitle(url, url))
    embed.setAuthor(EmbedAuthor(title, null, null))
    return embed
}

private fun makeEmbed(
    stream: Stream,
    game: Game,
    thumbnail: InputStream?,
    twitchName: String,
    currentSegment: StreamElement? = null
): WebhookMessageBuilder {
    val embed = makeEmbedBase(stream.title, "https://www.twitch.tv/$twitchName").let { builder ->
        builder.addField(
            EmbedField(
                true, "Playing", game.name
            )
        )
        builder.addField(
            EmbedField(
                true, "Started At", stream.startedAt.format(DateTimeFormatter.RFC_1123_DATE_TIME)
            )
        )
        if (currentSegment != null) {
            builder.setDescription("Start watching at ${currentSegment.toVodLink("")}")
        }
        builder.build()
    }

    return WebhookMessageBuilder().apply {
        setAllowedMentions(AllowedMentions().withParseRoles(true))
        if (thumbnail != null)
            addFile("thumbnail.jpg", thumbnail)
        addEmbeds(embed)
    }
}