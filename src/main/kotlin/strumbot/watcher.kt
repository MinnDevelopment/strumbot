package strumbot

import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.receive.ReadonlyMessage
import club.minnced.discord.webhook.send.WebhookEmbed
import club.minnced.discord.webhook.send.WebhookEmbed.EmbedTitle
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.then
import net.dv8tion.jda.api.JDA
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

data class StreamElement(val game: Game, val timestamp: Int)

class StreamWatcher(
    private val twitch: TwitchApi,
    private val jda: JDA,
    private val configuration: Configuration) {

    @Volatile private var currentElement: StreamElement? = null
    private val rankByType: MutableMap<String, String> = mutableMapOf()
    private val timestamps: MutableList<StreamElement> = mutableListOf()

    private fun getRole(type: String): String {
        if (type !in rankByType) {
            val roleId = jda.getRolesByName(type, true).firstOrNull()?.id
            if (roleId == null)
                return "0"
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
                    twitch.getStreamByLogin("elajjaz")
                        .switchIfEmpty {
                            handleOffline(webhook).then(Mono.empty())
                        }
                        .flatMap { stream ->
                            if (stream.gameId != currentElement?.game?.gameId) {
                                handleUpdate(stream, webhook)
                            } else {
                                // Unchanged
                                Mono.empty<Unit>()
                            }
                        }
                } else {
                    twitch.getStreamByLogin("elajjaz")
                        .flatMap { stream ->
                            Mono.zip(stream.toMono(), twitch.getGame(stream), twitch.getThumbnail(stream))
                        }
                        .flatMap { tuple ->
                            handleGoLive(tuple, webhook)
                        }
                }
            }
            .publishOn(scheduler)
            .subscribe()
    }

    private fun <T> withPing(roleId: String, block: (String) -> Mono<T>): Mono<T> {
        val role = jda.getRoleById(roleId)
        val mentionable = role?.manager?.setMentionable(true)?.asMono()
        return (mentionable ?: Mono.empty())
            .then { block("<@&$roleId>") }
            .flatMap { result ->
                val notMentionable = role?.manager?.setMentionable(false)?.asMono() ?: Mono.empty()
                notMentionable.thenReturn(result)
            }
    }

    private fun toTwitchTimestamp(timestamp: Int): String {
        val duration = Duration.ofSeconds(timestamp.toLong())
        val hours = duration.toHours()
        val minutes = duration.minusHours(hours).toMinutes()
        val seconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds()
        return String.format("%02dh%02dm%02ds", hours, minutes, seconds)
    }

    private fun handleOffline(webhook: WebhookClient): Mono<Void> {
        println("Stream went offline!")
        timestamps.add(currentElement!!)
        currentElement = null
        val timestamps = this.timestamps.toList()
        this.timestamps.clear()

        return twitch.getUserIdByLogin("elajjaz")
            .flatMap(twitch::getLatestBroadcastByUser)
            .flatMap {
                Mono.zip(it.toMono(), twitch.getThumbnail(it))
            }
            .flatMap { tuple ->
                val video = tuple.t1
                val thumbnail = tuple.t2
                val embed = makeEmbedBase(video.title)

                val index = timestamps.map {
                    val twitchTimestamp = toTwitchTimestamp(it.timestamp)
                    val url = "[$twitchTimestamp](${video.url}?t=$twitchTimestamp)"
                    "${it.game.name} ($url)"
                }.joinToString("\n")

                embed.addField(
                    WebhookEmbed.EmbedField(
                    false, "Timestamps", index
                ))

                val roleId = getRole("vod")


                withPing(roleId) { mention ->
                    val message = WebhookMessageBuilder()
                        .setContent("$mention VOD [${video.duration}]")
                        .addEmbeds(embed.build())
                        .addFile("thumbnail", thumbnail)
                        .build()

                    Mono.fromFuture { webhook.send(message) }
                }
            }
            .then()
    }

    private fun handleGoLive(
        tuple: Tuple3<Stream, Game, InputStream>,
        webhook: WebhookClient
    ): Mono<ReadonlyMessage> {
        val stream = tuple.t1
        val game = tuple.t2
        val thumbnail = tuple.t3

        println("Started with game ${game.gameId}")
        val roleId = getRole("live")
        currentElement = StreamElement(game, 0)
        return withPing(roleId) { mention ->
            val embed = makeEmbed(stream, game, thumbnail)
                .setContent("$mention Elajjaz is live with ${game.name}!")
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
                val timestamp = stream.startedAt.until(OffsetDateTime.now(), ChronoUnit.SECONDS).toInt()
                currentElement = StreamElement(game, timestamp)
                Mono.zip(game.toMono(), twitch.getThumbnail(stream))
            }
            .flatMap { tuple ->
                val game = tuple.t1
                val thumbnail = tuple.t2
                val roleId = getRole("update")
                withPing(roleId) { mention ->
                    val embed = makeEmbed(stream, game, thumbnail)
                        .setContent("$mention Elajjaz switched game to ${game.name}!")
                        .build()
                    Mono.fromFuture { webhook.send(embed) }
                }
            }
    }
}

private fun makeEmbedBase(title: String): WebhookEmbedBuilder {
    val embed = WebhookEmbedBuilder()

    embed.setColor(0x6441A4)
    embed.setDescription("**[https://twitch.tv/elajjaz](https://twitch.tv/elajjaz)**")
    embed.setImageUrl("attachment://thumbnail.jpg")
    embed.setTitle(EmbedTitle(title, null))

    return embed
}

private fun makeEmbed(
    stream: Stream,
    game: Game,
    thumbnail: InputStream
): WebhookMessageBuilder {
    val embed = makeEmbedBase(stream.title).let { builder ->
        builder.addField(
            WebhookEmbed.EmbedField(
                true, "Playing", game.name
            )
        )
        builder.addField(
            WebhookEmbed.EmbedField(
                true, "Started At", stream.startedAt.format(DateTimeFormatter.RFC_1123_DATE_TIME)
            )
        )
        builder.build()
    }

    return WebhookMessageBuilder()
        .addFile("thumbnail.jpg", thumbnail)
        .addEmbeds(embed)
}