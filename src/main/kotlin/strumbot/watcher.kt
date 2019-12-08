package strumbot

import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.receive.ReadonlyMessage
import club.minnced.discord.webhook.send.WebhookEmbed
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import club.minnced.discord.webhook.send.WebhookMessage
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.then
import net.dv8tion.jda.api.JDA
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.switchIfEmpty
import reactor.core.publisher.toMono
import reactor.util.function.Tuple3
import java.io.InputStream
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ScheduledExecutorService

data class StreamElement(val game: Game, val timestamp: Int)

class StreamWatcher(val twitch: TwitchApi, val jda: JDA, val configuration: Configuration) {
    @Volatile var currentElement: StreamElement? = null
    val rankByType: MutableMap<String, String> = mutableMapOf()
    val timestamps: MutableList<StreamElement> = mutableListOf()

    fun getRole(type: String): String {
        if (type !in rankByType) {
            val roleId = jda.getRolesByName(type, true).firstOrNull()?.id
            if (roleId == null)
                return "0"
            rankByType[type] = roleId
        }
        return rankByType[type] ?: "0"
    }

    fun run(pool: ScheduledExecutorService) {
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
                            handleOffline()
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

    private fun handleOffline(): Mono<Stream> {
        println("Stream went offline!")
        timestamps.add(currentElement!!)
        currentElement = null
        //TODO: Handle stream goes offline (vod list)
        timestamps.clear()
        return Mono.empty() //TODO: Stream went offline!
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
            val embed = makeEmbed(stream, game, mention, thumbnail)
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
                //TODO: Change message for game switched
                withPing(roleId) { mention ->
                    val embed = makeEmbed(stream, game, mention, thumbnail)
                    Mono.fromFuture { webhook.send(embed) }
                }
            }
    }
}

private fun makeEmbed(
    stream: Stream,
    game: Game,
    role: String,
    thumbnail: InputStream
): WebhookMessage {
    val embed = WebhookEmbedBuilder().let { builder ->
        builder.setTitle(WebhookEmbed.EmbedTitle(stream.title, null))
        builder.setDescription("**[https://twitch.tv/elajjaz](https://twitch.tv/elajjaz)**")
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
        builder.setColor(0x6441A4)
        builder.setImageUrl("attachment://thumbnail.jpg")
        builder.build()
    }

    return WebhookMessageBuilder()
        .setContent("$role Elajjaz is live with ${game.name}")
        .addFile("thumbnail.jpg", thumbnail)
        .addEmbeds(embed)
        .build()
}