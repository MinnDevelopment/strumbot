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

@file:JvmName("Main")
package strumbot

import club.minnced.jda.reactor.ReactiveEventManager
import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import dev.minn.jda.ktx.CoroutineEventManager
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.interactions.choice
import dev.minn.jda.ktx.interactions.option
import dev.minn.jda.ktx.interactions.upsertCommand
import dev.minn.jda.ktx.light
import dev.minn.jda.ktx.listener
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.guild.GenericGuildEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent
import net.dv8tion.jda.api.exceptions.HierarchyException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.RoleAction
import net.dv8tion.jda.api.utils.AllowedMentions
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toFlux
import reactor.util.retry.Retry
import java.lang.Integer.max
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("Main") as Logger

fun getThreadCount(): Int = max(2, ForkJoinPool.getCommonPoolParallelism())

private val pool = Executors.newScheduledThreadPool(getThreadCount()) {
    thread(start=false, name="Worker-Thread", isDaemon=true, block=it::run)
}

private val poolScheduler = Schedulers.fromExecutor(pool)

fun main() {
    AllowedMentions.setDefaultMentions(EnumSet.of(Message.MentionType.ROLE))

    val configuration = loadConfiguration("config.json")
    val okhttp = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 20, TimeUnit.SECONDS))
        .build()

    log.info("Initializing twitch api")
    val twitch = createTwitchApi(
        okhttp, poolScheduler,
        configuration.twitchClientId, configuration.twitchClientSecret
    ).block()!!

    log.info("Initializing discord connection")
    val manager = CoroutineEventManager()
    manager.initCommands(configuration)
    manager.initRoles(configuration)

    val jda = light(configuration.token, enableCoroutines=false, timeout=1.minutes) {
        setEventManager(manager)
        setHttpClient(okhttp)
        setCallbackPool(pool)
        setGatewayPool(pool)
        setRateLimitPool(pool)
    }

    configuration.logging?.let {
        WebhookAppender.init(jda, it)
    }

    // Cycling streaming status
    val activityService = ActivityService(jda)
    activityService.start()

    setupRankListener(jda, configuration)

    jda.awaitReady()

    val watchedStreams = mutableMapOf<String, StreamWatcher>()
    for (userLogin in configuration.twitchUser) {
        val key = userLogin.lowercase(Locale.ROOT) // make sure we don't insert things twice
        watchedStreams[key] = StreamWatcher(twitch, jda, configuration, userLogin, activityService)
    }

    startTwitchService(twitch, watchedStreams, poolScheduler)
        .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMinutes(1)).transientErrors(true))
        .doFinally {
            log.warn("Twitch service terminated unexpectedly with signal {}", it)
            jda.shutdownNow()
        }.subscribe()

    System.gc()
}

private fun CoroutineEventManager.initRoles(configuration: Configuration) {
    listener<GenericGuildEvent> { event ->
        if (event !is GuildReadyEvent && event !is GuildJoinEvent) return@listener
        val guild = event.guild

        if (!filterId(guild, configuration.guildId)) return@listener

        configuration.ranks.values
            .asSequence()
            .filter { guild.getRolesByName(it, true).isEmpty() }
            .map { guild.createRole().setName(it) }
            .forEach {
                val role = it.await()
                log.info("Created role ${role.name} in ${guild.name}")
            }
    }
}

private fun CoroutineEventManager.initCommands(configuration: Configuration) {
    listener<GenericGuildEvent> { event ->
        if (event !is GuildReadyEvent && event !is GuildJoinEvent) return@listener
        val guild = event.guild

        guild.upsertCommand("rank", "Add or remove one of the notification roles") {
            option<String>("role", "The role to assign or remove you from", required = true) {
                configuration.ranks.forEach { (_, value) ->
                    choice(value, value)
                }
            }
        }.queue()
    }
}

private fun setupRankListener(jda: JDA, configuration: Configuration) {
    jda.listener<SlashCommandEvent>(timeout = 1.minutes) { event ->
        if (event.name != "rank") return@listener

        val guild = event.guild ?: return@listener
        val type = event.getOption("role")?.asString ?: ""
        val role = guild.getRoleById(jda.getRoleByType(configuration, type)) ?: return@listener
        val member = event.member ?: return@listener
        event.deferReply(true).queue() // This is required to handle delayed response
        event.hook.setEphemeral(true)

        try {
            val added = toggleRole(member, role)
            event.hook.sendMessage(if (added) "Added the role" else "Removed the role").await()
        } catch (ex: PermissionException) {
            event.hook.sendMessage(handlePermissionError(ex, role)).await()
        }
    }
}

private suspend fun toggleRole(
    member: Member,
    role: Role
) = if (role in member.roles) {
    log.debug("Removing ${role.name} from ${member.user.asTag}")
    role.guild.removeRoleFromMember(member, role).await()
    false
} else {
    log.debug("Adding ${role.name} to ${member.user.asTag}")
    role.guild.addRoleToMember(member, role).await()
    true
}

private fun handlePermissionError(
    error: PermissionException,
    role: Role?
): String {
    return when (error) {
        is InsufficientPermissionException ->
            "I'm missing the permission **${error.permission.getName()}**"
        is HierarchyException ->
            "I can't assign a role to you because the role is too high! Role: ${role?.name}"
        else ->
            "Encountered an error: `$error`!"
    }
}
