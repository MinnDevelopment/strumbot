@file:JvmName("Main")
package strumbot

import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import club.minnced.jda.reactor.toMono
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import okhttp3.OkHttpClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import reactor.core.scheduler.Schedulers
import java.time.Duration.ofSeconds
import java.util.EnumSet.noneOf
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import kotlin.concurrent.thread

private val pool = Executors.newScheduledThreadPool(ForkJoinPool.getCommonPoolParallelism() * 2) {
    thread(start=false, name="Worker-Thread", isDaemon=true, block=it::run)
}

private val poolScheduler = Schedulers.fromExecutor(pool)

var shutdown = false

fun streams(twitchApi: TwitchApi): Flux<Stream> {
    return Flux.interval(ofSeconds(0), ofSeconds(20))
        .takeUntil { shutdown }
        .flatMap { twitchApi.getStreamByLogin("elajjaz") }
}

fun main() {
    val configuration = loadConfiguration("config.json")
    val okhttp = OkHttpClient()
    val twitch = TwitchApi(
        okhttp, poolScheduler,
        configuration.twitchClientId, configuration.twitchClientSecret
    )

    val manager = createManager {
        this.scheduler = poolScheduler
    }

    val jda = JDABuilder(configuration.token)
        .setEventManager(manager)
        .setHttpClient(okhttp)
        .setEnabledCacheFlags(noneOf(CacheFlag::class.java))
        .setGuildSubscriptionsEnabled(false)
        .setCallbackPool(pool)
        .setGatewayPool(pool)
        .setRateLimitPool(pool)
        .build()

    jda.on<GuildMessageReceivedEvent>()
        .map { it.message }
        .filter { it.member != null }
        .filter { it.contentRaw.startsWith("?rank ") }
        .flatMap {
            val member = it.member!!
            val mention = member.asMention
            // The role name is after the command
            val roleName = it.contentRaw.removePrefix("?rank ").toLowerCase()
            // We shouldn't let users assign themselves any other roles like mod
            if (roleName !in configuration.ranks) {
                return@flatMap it.channel.sendMessage("$mention, That role does not exist!").asMono()
            }

            // Check if role by that name exists
            val role = it.guild.getRolesByName(roleName, false).firstOrNull()
            if (role != null) {
                // Add the role to the member and send a success message
                it.guild.addRoleToMember(member, role).asMono()
                    .flatMap { _ ->
                        it.channel.sendMessage("$mention, you joined **${role.name}.").asMono()
                    }
            } else {
                // Send a failure message, unknown role
                it.channel.sendMessage("$mention I don't know that role!").asMono()
            }
        }
        .onErrorContinue { t, _ -> t.printStackTrace() } //TODO: Use logger?
        .subscribe()

    val webhook = WebhookClientBuilder(configuration.webhookUrl)
        .setExecutorService(pool)
        .setHttpClient(okhttp)
        .setWait(false)
        .build()

    streams(twitch)
        .flatMap {
            Mono.zip(it.toMono(), twitch.getGame(it))
        }
        .flatMap {
            val role = jda.getRolesByName("live", false).firstOrNull()?.asMention ?: ""
            webhook.send("$role Elajjaz is live with ${it.t2.name}").toMono()
        }
        .subscribe()
}