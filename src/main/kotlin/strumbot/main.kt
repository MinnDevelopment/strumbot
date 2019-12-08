@file:JvmName("Main")
package strumbot

import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import club.minnced.jda.reactor.then
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.scheduler.Schedulers
import java.util.EnumSet.noneOf
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private val log = LoggerFactory.getLogger("Main") as Logger

private val pool = Executors.newScheduledThreadPool(2) {
    thread(start=false, name="Worker-Thread", isDaemon=true, block=it::run)
}

private val poolScheduler = Schedulers.fromExecutor(pool)

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

    setupRankListener(jda, configuration)

    jda.awaitReady()
    StreamWatcher(twitch, jda, configuration).run(pool, poolScheduler)
}

private fun setupRankListener(jda: JDA, configuration: Configuration) {
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
                // Add/Remove the role to the member and send a success message
                if (member.roles.any { it.idLong == role.idLong }) {
                    log.debug("Adding ${role.name} to ${member.user.asTag}")
                    it.guild.removeRoleFromMember(member, role).asMono().then {
                        it.channel.sendMessage("$mention, you left **${role.name}**.").asMono()
                    }
                } else {
                    log.debug("Removing ${role.name} from ${member.user.asTag}")
                    it.guild.addRoleToMember(member, role).asMono().then {
                        it.channel.sendMessage("$mention, you joined **${role.name}**.").asMono()
                    }
                }
            } else {
                // Send a failure message, unknown role
                it.channel.sendMessage("$mention I don't know that role!").asMono()
            }
        }
        .onErrorContinue { t, _ -> log.error("Rank service encountered exception", t) }
        .subscribe()
}