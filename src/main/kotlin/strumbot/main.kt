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

@file:JvmName("Main")
package strumbot

import club.minnced.jda.reactor.ReactiveEventManager
import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.exceptions.HierarchyException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.requests.GatewayIntent
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
    val manager = createManager { this.scheduler = poolScheduler }
    manager.initCommands(configuration)
    manager.initRoles(configuration)
    val jda = JDABuilder.createLight(configuration.token, GatewayIntent.GUILD_MESSAGES)
        .setEventManager(manager)
        .setHttpClient(okhttp)
        .setCallbackPool(pool)
        .setGatewayPool(pool)
        .setRateLimitPool(pool)
        .build()

    configuration.logging?.let {
        WebhookAppender.init(jda, it)
    }

    // Cycling streaming status
    val activityService = ActivityService(jda, poolScheduler)
    activityService.start()

    setupRankListener(jda, configuration)

    jda.awaitReady()

    val watchedStreams = mutableMapOf<String, StreamWatcher>()
    for (userLogin in configuration.twitchUser) {
        watchedStreams[userLogin] = StreamWatcher(twitch, jda, configuration, userLogin, activityService)
    }

    startTwitchService(twitch, watchedStreams, poolScheduler)
        .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMinutes(1)).transientErrors(true))
        .doFinally {
            log.warn("Twitch service terminated unexpectedly with signal {}", it)
            jda.shutdownNow()
        }.subscribe()

    System.gc()
}

private fun ReactiveEventManager.initRoles(configuration: Configuration) {
    val listener = Flux.merge(
        on<GuildReadyEvent>().map(GuildReadyEvent::getGuild),
        on<GuildJoinEvent>().map(GuildJoinEvent::getGuild)
    )

    val ranks = configuration.ranks.values
    listener
        .filter { filterId(it, configuration.guildId) }
        .flatMap { guild ->
            ranks.toFlux()
                 .filter { guild.getRolesByName(it, true).isEmpty() }
                 .map { guild.createRole().setName(it) }
                 .flatMap(RoleAction::asMono)
        }
        .subscribe { log.info("Created role ${it.name} in ${it.guild.name}") }
}

private fun ReactiveEventManager.initCommands(configuration: Configuration) {
    on<GuildReadyEvent>().map { it.guild }
        .mergeWith(on<GuildJoinEvent>().map { it.guild })
        .flatMap { guild ->
            guild.upsertCommand("rank", "Add or remove one of the notification roles") // TODO: Use jda-ktx
                .addOptions(OptionData(OptionType.STRING, "role", "The role to assign or remove you from").also {
                    configuration.ranks.forEach { (_, value) -> it.addChoice(value, value) }
                    it.isRequired = true
                })
                .asMono()
        }
        .subscribe()
}

private fun setupRankListener(jda: JDA, configuration: Configuration) {
    jda.onCommand("rank")
       .flatMap { event ->
           val guild = event.guild ?: return@flatMap Mono.empty<Unit>()
           val type = event.getOption("role")?.asString ?: ""
           val role = guild.getRoleById(jda.getRoleByType(configuration, type)) ?: return@flatMap Mono.empty<Unit>()
           val member = event.member ?: return@flatMap Mono.empty<Unit>()
           event.deferReply(true).queue() // This is required to handle delayed response
           event.hook.setEphemeral(true)
           toggleRole(member, role).flatMap {
               event.hook.sendMessage(if (it) "Added the role" else "Removed the role").asMono()
           }.onErrorResume(PermissionException::class.java) {
               event.hook.sendMessage(handlePermissionError(it, role)).asMono()
           }
       }
       .retryWhen(Retry.indefinitely().filter { it !is Error })
       .subscribe()
}

private fun toggleRole(
    member: Member,
    role: Role
): Mono<Boolean> = Mono.defer {
    if (role in member.roles) {
        log.debug("Removing ${role.name} from ${member.user.asTag}")
        role.guild.removeRoleFromMember(member, role).asMono().thenReturn(false)
    } else {
        log.debug("Adding ${role.name} to ${member.user.asTag}")
        role.guild.addRoleToMember(member, role).asMono().thenReturn(true)
    }
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
