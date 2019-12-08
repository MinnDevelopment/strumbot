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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.switchIfEmpty
import reactor.core.publisher.toMono
import reactor.core.scheduler.Scheduler
import reactor.util.function.Tuple3
import java.io.InputStream
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ScheduledExecutorService

const val OFFLINE_DELAY = 2L * 60L // 2 minutes

data class StreamElement(val game: Game, val timestamp: Int, val videoId: String)

class StreamWatcher(
    private val twitch: TwitchApi,
    private val jda: JDA,
    private val configuration: Configuration) {

    // TODO: Handle stream hickups (might go offline and back online within the 10 second window)
    @Volatile private var currentElement: StreamElement? = null
    private var offlineTimestamp = 0L
    private var streamStarted = 0L
    private val rankByType: MutableMap<String, String> = mutableMapOf()
    private val timestamps: MutableList<StreamElement> = mutableListOf()

    private fun getRole(type: String): String {
        if (type !in rankByType) {
            val roleId = jda.getRolesByName(type, true).firstOrNull()?.id ?: return "0"
            rankByType[type] = roleId
        }
        return rankByType[type] ?: "0"
    }

    fun run(pool: ScheduledExecutorService, scheduler: Scheduler) {
        val webhook = WebhookClientBuilder(configuration.webhookUrl)
            .setExecutorService(pool)
            .setHttpClient(jda.httpClient)
            .setWait(true)
            .build()

        Flux.interval(Duration.ofSeconds(0), Duration.ofSeconds(10))
            .flatMap {
                if (currentElement != null) {
                    twitch.getStreamByLogin(configuration.twitchUser)
                        .switchIfEmpty {
                            handleOffline(webhook).then(Mono.empty())
                        }
                        .flatMap { stream ->
                            offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                            if (stream.gameId != currentElement?.game?.gameId) {
                                handleUpdate(stream, webhook)
                            } else {
                                // Unchanged
                                Mono.empty<Unit>()
                            }
                        }
                } else {
                    twitch.getStreamByLogin(configuration.twitchUser)
                        .flatMap { stream ->
                            Mono.zip(stream.toMono(), twitch.getGame(stream), twitch.getThumbnail(stream))
                        }
                        .flatMap { tuple ->
                            offlineTimestamp = 0 // We can skip one offline event since we are currently live and it might hickup
                            handleGoLive(tuple, webhook)
                        }
                }
            }
            .onErrorContinue { t, _ -> t.printStackTrace() } //TODO: User logger
            .doOnEach { System.gc() }
            .publishOn(scheduler)
            .subscribe()
    }

    private fun <T> withPing(roleId: String, block: (String) -> Mono<T>): Mono<T> {
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

    private fun toTwitchTimestamp(timestamp: Int): String {
        val duration = Duration.ofSeconds(timestamp.toLong())
        val hours = duration.toHours()
        val minutes = duration.minusHours(hours).toMinutes()
        val seconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds()
        return "%02dh%02dm%02ds".format(hours, minutes, seconds)
    }

    private fun handleOffline(webhook: WebhookClient): Mono<ReadonlyMessage> {
        if (offlineTimestamp == 0L) {
            offlineTimestamp = OffsetDateTime.now().toEpochSecond()
            return Mono.empty()
        } else if (OffsetDateTime.now().toEpochSecond() - offlineTimestamp < OFFLINE_DELAY) {
            return Mono.empty()
        }

        println("Stream went offline!")
        jda.presence.activity = null
        timestamps.add(currentElement!!)
        val videoId = currentElement!!.videoId
        currentElement = null
        val timestamps = this.timestamps.toList()
        this.timestamps.clear()

        return Flux.fromIterable(timestamps)
            .map {
                val url = "https://www.twitch.tv/videos/${it.videoId}"
                if (it.timestamp == 0) "${it.game.name} ( [0:00:00]($url) )"
                else {
                    val twitchTimestamp = toTwitchTimestamp(it.timestamp)
                    "${it.game.name} ( [$twitchTimestamp](${url}?t=$twitchTimestamp) )"
                }
            }
            .reduce(StringBuilder()) { a, b -> a.append("\n").append(b) }
            .flatMap { Mono.zip(it.toMono(), twitch.getVideoById(videoId)) }
            .flatMap { Mono.zip(it.t1.toMono(), it.t2.toMono(), twitch.getThumbnail(it.t2)) }
            .flatMap {
                val index = it.t1
                val video = it.t2
                val thumbnail = it.t3
                val embed = makeEmbedBase(video.title, configuration.twitchUser)
                embed.addField(EmbedField(false, "Time Stamps", index.toString()))

                val roleId = getRole("vod")
                withPing(roleId) { mention ->
                    val duration = toTwitchTimestamp((offlineTimestamp - streamStarted).toInt())
                    val message = WebhookMessageBuilder()
                        .setContent("$mention VOD [$duration]")
                        .setUsername("Stream Notifications")
                        .addEmbeds(embed.build())
                        .addFile("thumbnail", thumbnail)
                        .build()

                    Mono.fromFuture { webhook.send(message) }
                }
            }
    }

    private fun handleGoLive(
        tuple: Tuple3<Stream, Game, InputStream>,
        webhook: WebhookClient
    ): Mono<ReadonlyMessage> {
        val stream = tuple.t1
        val game = tuple.t2
        val thumbnail = tuple.t3

        println("Started with game ${game.gameId}")
        jda.presence.activity = Activity.streaming(game.name, "https://www.twitch.tv/${configuration.twitchUser}")
        streamStarted = stream.startedAt.toEpochSecond()
        val roleId = getRole("live")
        currentElement = StreamElement(game, 0, stream.streamId)
        return withPing(roleId) { mention ->
            val embed = makeEmbed(stream, game, thumbnail, configuration.twitchUser)
                .setContent("$mention ${configuration.twitchUser} is live with ${game.name}!")
                .setUsername("Stream Notifications")
                .build()
            Mono.fromFuture { webhook.send(embed) }
        }
    }

    private fun handleUpdate(
        stream: Stream,
        webhook: WebhookClient
    ): Mono<ReadonlyMessage> {
        println("Changed game ${currentElement?.game?.gameId} -> ${stream.gameId}")
        timestamps.add(currentElement!!)
        return twitch.getGame(stream)
            .flatMap { game ->
                jda.presence.activity = Activity.streaming(game.name, "https://www.twitch.tv/${configuration.twitchUser}")
                val timestamp = stream.startedAt.until(OffsetDateTime.now(), ChronoUnit.SECONDS).toInt()
                currentElement = StreamElement(game, timestamp, stream.streamId)
                Mono.zip(game.toMono(), twitch.getThumbnail(stream))
            }
            .flatMap { tuple ->
                val game = tuple.t1
                val thumbnail = tuple.t2
                val roleId = getRole("update")
                withPing(roleId) { mention ->
                    val embed = makeEmbed(stream, game, thumbnail, configuration.twitchUser)
                        .setContent("$mention ${configuration.twitchUser} switched game to ${game.name}!")
                        .setUsername("Stream Notifications")
                        .build()
                    Mono.fromFuture { webhook.send(embed) }
                }
            }
    }
}

private fun makeEmbedBase(title: String, twitchName: String): WebhookEmbedBuilder {
    val embed = WebhookEmbedBuilder()
    embed.setColor(0x6441A4)
    embed.setImageUrl("attachment://thumbnail.jpg")
    embed.setTitle(EmbedTitle("https://twitch.tv/$twitchName", "https://twitch.tv/$twitchName"))
    embed.setAuthor(EmbedAuthor(title, null, null))
    return embed
}

private fun makeEmbed(
    stream: Stream,
    game: Game,
    thumbnail: InputStream,
    twitchName: String
): WebhookMessageBuilder {
    val embed = makeEmbedBase(stream.title, twitchName).let { builder ->
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