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
import reactor.core.publisher.switchIfEmpty
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
import java.util.concurrent.ScheduledExecutorService

private val log = LoggerFactory.getLogger(StreamWatcher::class.java) as Logger
const val OFFLINE_DELAY = 2L * 60L // 2 minutes
const val HOOK_NAME = "Stream Notifications"

private val ignoredErrors = setOf<Class<*>>(
    SocketException::class.java,              // Issues on socket creation
    SocketTimeoutException::class.java,       // Timeouts
    UnknownHostException::class.java          // DNS errors
)

data class StreamElement(val game: Game, val timestamp: Int, val videoId: String)

class StreamWatcher(
    private val twitch: TwitchApi,
    private val jda: JDA,
    private val configuration: Configuration) {

    @Volatile private var currentElement: StreamElement? = null
    private var offlineTimestamp = 0L
    private var streamStarted = 0L
    private val rankByType: MutableMap<String, String> = mutableMapOf()
    private val timestamps: MutableList<StreamElement> = mutableListOf()

    fun run(pool: ScheduledExecutorService, scheduler: Scheduler) {
        log.info("Listening for stream from ${configuration.twitchUser}")
        val webhook = WebhookClientBuilder(configuration.streamNotifications)
            .setExecutorService(pool)
            .setHttpClient(jda.httpClient)
            .setWait(true)
            .build()

        Flux.interval(Duration.ofSeconds(0), Duration.ofSeconds(10), scheduler)
            .flatMap {
                if (currentElement != null) {
                    twitch.getStreamByLogin(configuration.twitchUser)
                        .switchIfEmpty {
                            handleOffline(webhook).then(Mono.empty())
                        }
                        .flatMap { Mono.zip(it.toMono(), twitch.getLatestBroadcastByUser(it.userId).map(Video::id)) }
                        .flatMap { tuple ->
                            val (stream, videoId) = tuple
                            offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                            if (stream.gameId != currentElement?.game?.gameId) {
                                handleUpdate(stream, videoId, webhook)
                            } else {
                                // Unchanged
                                Mono.empty<Unit>()
                            }
                        }
                } else {
                    twitch.getStreamByLogin(configuration.twitchUser)
                        .flatMap { stream ->
                            Mono.zip(stream.toMono(),
                                twitch.getGame(stream),
                                twitch.getLatestBroadcastByUser(stream.userId).map(Video::id),
                                twitch.getThumbnail(stream))
                        }
                        .flatMap { tuple ->
                            offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                            handleGoLive(tuple, webhook)
                        }
                }
            }
            .retry(Exceptions::isOverflow) // re-subscribe on overflow (fell behind on requests due to internet issues maybe)
            .retry { it::class.java in ignoredErrors } // re-subscribe on internet issues
            .onErrorContinue { t, _ ->
                if (!Exceptions.isOverflow(t) && t::class.java !in ignoredErrors)
                    log.error("Error in twitch stream service", t)
            }
            .doOnEach { System.gc() }
            .doFinally { log.warn("Twitch service terminated unexpectedly with signal {}", it) }
            .subscribe()
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
        jda.presence.activity = null
        timestamps.add(currentElement!!)
        val videoId = currentElement!!.videoId
        currentElement = null
        val timestamps = this.timestamps.toList()
        val firstSegment = timestamps.first()
        this.timestamps.clear()

        return Flux.fromIterable(timestamps)
            .map {
                val url = "https://www.twitch.tv/videos/${it.videoId}"
                val twitchTimestamp = toTwitchTimestamp(it.timestamp)
                "${it.game.name} ( [$twitchTimestamp](${url}?t=$twitchTimestamp) )"
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
                    val duration = toTwitchTimestamp((offlineTimestamp - streamStarted).toInt())
                    val message = WebhookMessageBuilder()
                        .setContent("$mention VOD [$duration]")
                        .setUsername(HOOK_NAME)
                        .addEmbeds(embed.build())
                        .addFile("thumbnail.jpg", thumbnail)
                        .build()

                    fireEvent("vod") {
                        Mono.fromFuture { webhook.send(message) }
                    }
                }
            }
    }

    private fun handleGoLive(
        tuple: Tuple4<Stream, Game, String, InputStream>,
        webhook: WebhookClient
    ): Mono<ReadonlyMessage> {
        val (stream, game, videoId, thumbnail) = tuple

        log.info("Stream started with game ${game.name} (${game.gameId})")
        jda.presence.activity = Activity.streaming(game.name, "https://www.twitch.tv/${configuration.twitchUser}")
        streamStarted = stream.startedAt.toEpochSecond()
        currentElement = StreamElement(game, 0, videoId)
        return withPing("live") { mention ->
            val embed = makeEmbed(stream, game, thumbnail, configuration.twitchUser)
                .setContent("$mention ${configuration.twitchUser} is live with **${game.name}**!")
                .setUsername(HOOK_NAME)
                .build()
            fireEvent("live") {
                Mono.fromFuture { webhook.send(embed) }
            }
        }
    }

    private fun handleUpdate(
        stream: Stream,
        videoId: String,
        webhook: WebhookClient
    ): Mono<ReadonlyMessage> {
        log.info("Stream changed game ${currentElement?.game?.gameId} -> ${stream.gameId}")
        timestamps.add(currentElement!!)
        return twitch.getGame(stream)
            .flatMap { game ->
                jda.presence.activity = Activity.streaming(game.name, "https://www.twitch.tv/${configuration.twitchUser}")
                val timestamp = stream.startedAt.until(OffsetDateTime.now(), ChronoUnit.SECONDS).toInt()
                currentElement = StreamElement(game, timestamp, videoId)
                Mono.zip(game.toMono(), twitch.getThumbnail(stream))
            }
            .flatMap { tuple ->
                val (game, thumbnail) = tuple
                withPing("update") { mention ->
                    val embed = makeEmbed(stream, game, thumbnail, configuration.twitchUser)
                        .setContent("$mention ${configuration.twitchUser} switched game to **${game.name}**!")
                        .setUsername(HOOK_NAME)
                        .build()
                    fireEvent("update") {
                        Mono.fromFuture { webhook.send(embed) }
                    }
                }
            }
    }

    /// HELPERS

    private fun getRole(type: String): String {
        val roleName = configuration.ranks[type] ?: "0"
        if (type !in rankByType) {
            val roleId = jda.getRolesByName(roleName, true).firstOrNull()?.id ?: return "0"
            rankByType[type] = roleId
        }
        return rankByType[type] ?: "0"
    }

    private fun toTwitchTimestamp(timestamp: Int): String {
        val duration = Duration.ofSeconds(timestamp.toLong())
        val hours = duration.toHours()
        val minutes = duration.minusHours(hours).toMinutes()
        val seconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds()
        return "%02dh%02dm%02ds".format(hours, minutes, seconds)
    }

    private inline fun <T> withPing(type: String, crossinline block: (String) -> Mono<T>): Mono<T> {
        val roleId = getRole(type)
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

    private inline fun <T> fireEvent(type: String, block: () -> Mono<T>): Mono<T> {
        return if (type in configuration.events) {
            block()
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