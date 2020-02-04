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
import club.minnced.discord.webhook.send.WebhookEmbed.*
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.then
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import reactor.util.function.Tuple4
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

data class Timestamps(val display: String, val twitchFormat: String)
data class StreamElement(val game: Game, val timestamp: Int, val videoId: String)

class StreamWatcher(
    private val twitch: TwitchApi,
    private val jda: JDA,
    private val configuration: Configuration,
    private val userLogin: String,
    private val pool: ScheduledExecutorService,
    private val activityService: ActivityService) {

    @Volatile private var currentElement: StreamElement? = null
    private var offlineTimestamp = 0L
    private var streamStarted = 0L
    private var currentActivity: Activity? = null
    private val rankByType: MutableMap<String, String> = mutableMapOf()
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
                stream == null -> handleOffline(webhook)

                // 2. The stream was online and has switched the game
                // => Send update game notification (update event)
                stream.gameId != currentElement?.game?.gameId -> {
                    offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                    twitch.getVideoByStream(stream)
                        .map(Video::id)
                        .flatMap { handleUpdate(stream, it, webhook) }
                }

                // 3. The stream was online and has not switched the game
                // => Do nothing
                else -> {
                    offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                    Mono.empty<ReadonlyMessage>()
                }
            }
        } else {
            // 4. The stream was offline and has come online
            // => Send go live notification (live event)
            if (stream != null) {
                val getStream = stream.toMono()
                val getGame = twitch.getGame(stream)
                val getVod = twitch.getVideoByStream(stream).map(Video::id)
                val getThumbnail = twitch.getThumbnail(stream)
                Mono.zip(getStream, getGame, getVod, getThumbnail)
                    .flatMap { tuple ->
                        offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                        handleGoLive(tuple, webhook)
                    }
            } else {
                Mono.empty<ReadonlyMessage>()
            }
        }
    }

    private fun updateActivity(newActivity: Activity?) {
        currentActivity?.let(activityService::removeActivity)
        currentActivity = newActivity
        newActivity?.let(activityService::addActivity)
    }

    /// EVENTS

    private fun handleOffline(webhook: WebhookClient): Mono<ReadonlyMessage> {
        if (offlineTimestamp == 0L) {
            offlineTimestamp = OffsetDateTime.now().toEpochSecond()
            return Mono.empty()
        } else if (OffsetDateTime.now().toEpochSecond() - offlineTimestamp < OFFLINE_DELAY) {
            return Mono.empty()
        }

        log.info("Stream went offline!")
        updateActivity(null)
        timestamps.add(currentElement!!)
        val videoId = currentElement!!.videoId
        currentElement = null
        val timestamps = this.timestamps.toList()
        val firstSegment = timestamps.first()
        this.timestamps.clear()

        return Flux.fromIterable(timestamps)
            .map {
                val url = "https://www.twitch.tv/videos/${it.videoId}"
                val (timestamp, twitchTimestamp) = toTwitchTimestamp(it.timestamp)
                "[`$timestamp`]($url?t=$twitchTimestamp) ${it.game.name}"
            }
            .reduce(StringBuilder()) { a, b -> a.append("\n").append(b) }
            .flatMap { Mono.zip(it.toMono(), twitch.getVideoById(videoId)) }
            .flatMap { Mono.zip(it.t1.toMono(), it.t2.toMono(), twitch.getThumbnail(it.t2)) }
            .flatMap {
                val (index, video, thumbnail) = it
                val videoUrl = "https://www.twitch.tv/videos/${firstSegment.videoId}"
                val embed = makeEmbedBase(video.title, videoUrl)
                embed.addField(EmbedField(false, "Time Stamps", index.toString()))

                withPing("vod") { mention ->
                    val (_, duration) = toTwitchTimestamp((offlineTimestamp - streamStarted).toInt())
                    val message = WebhookMessageBuilder()
                        .setContent("$mention VOD [$duration]")
                        .setUsername(HOOK_NAME)
                        .addEmbeds(embed.build())
                        .addFile("thumbnail.jpg", thumbnail)
                        .build()

                    webhook.fireEvent("vod") { send(message) }
                }
            }
    }

    private fun handleGoLive(
        tuple: Tuple4<Stream, Game, String, InputStream>,
        webhook: WebhookClient
    ): Mono<ReadonlyMessage> {
        val (stream, game, videoId, thumbnail) = tuple
        log.info("Stream started with game ${game.name} (${game.gameId})")
        updateActivity(Activity.streaming(game.name, "https://www.twitch.tv/${userLogin}"))
        streamStarted = stream.startedAt.toEpochSecond()
        currentElement = StreamElement(game, 0, videoId)
        return withPing("live") { mention ->
            val embed = makeEmbed(stream, game, thumbnail, userLogin)
                .setContent("$mention $userLogin is live with **${game.name}**!")
                .setUsername(HOOK_NAME)
                .build()
            webhook.fireEvent("live") { send(embed) }
        }
    }

    private fun handleUpdate(
        stream: Stream,
        videoId: String,
        webhook: WebhookClient
    ): Mono<ReadonlyMessage> {
        timestamps.add(currentElement!!)
        return twitch.getGame(stream)
            .flatMap { game ->
                log.info("Stream changed game ${currentElement?.game?.name} -> ${game.name}")
                updateActivity(Activity.streaming(game.name, "https://www.twitch.tv/${userLogin}"))
                val timestamp = stream.startedAt.until(OffsetDateTime.now(), ChronoUnit.SECONDS).toInt()
                currentElement = StreamElement(game, timestamp, videoId)
                Mono.zip(game.toMono(), twitch.getThumbnail(stream))
            }
            .flatMap { tuple ->
                val (game, thumbnail) = tuple
                withPing("update") { mention ->
                    val embed = makeEmbed(stream, game, thumbnail, userLogin)
                        .setContent("$mention $userLogin switched game to **${game.name}**!")
                        .setUsername(HOOK_NAME)
                        .build()
                    webhook.fireEvent("update") { send(embed) }
                }
            }
    }

    /// HELPERS

    // Convert seconds to readable timestamp
    private fun toTwitchTimestamp(timestamp: Int): Timestamps {
        val duration = Duration.ofSeconds(timestamp.toLong())
        val hours = duration.toHours()
        val minutes = duration.minusHours(hours).toMinutes()
        val seconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds()
        return Timestamps(
            "%02d:%02d:%02d".format(hours, minutes, seconds),
            "%02dh%02dm%02ds".format(hours, minutes, seconds))
    }

    // Run callback with mentionable role
    private inline fun <T> withPing(type: String, crossinline block: (String) -> Mono<T>): Mono<T> {
        val roleId = jda.getRoleByType(configuration, type)
        val role = jda.getRoleById(roleId) ?: return block("")
        val guild = role.guild
        if (!guild.selfMember.hasPermission(Permission.MANAGE_ROLES)) {
            // we were missing permissions to make it mentionable but we can still try to mention it
            return block("<@&$roleId>")
        }

        return role.manager.setMentionable(true).asMono()
            .then { block("<@&$roleId>") }
            .flatMap { result ->
                val notMentionable = role.manager.setMentionable(false).asMono()
                notMentionable.thenReturn(result)
            }
    }

    // Fire webhook event if enabled in the configuration
    private inline fun <T> WebhookClient.fireEvent(type: String, crossinline block: WebhookClient.() -> CompletableFuture<T>): Mono<T> {
        return if (type in configuration.events) {
            Mono.fromFuture { block(this) }
        } else {
            Mono.empty()
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
    thumbnail: InputStream,
    twitchName: String
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
        builder.build()
    }

    return WebhookMessageBuilder()
        .addFile("thumbnail.jpg", thumbnail)
        .addEmbeds(embed)
}