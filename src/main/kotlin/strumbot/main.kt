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

import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import club.minnced.jda.reactor.then
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.exceptions.HierarchyException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.restaction.RoleAction
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.toFlux
import reactor.core.scheduler.Schedulers
import java.lang.Integer.min
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import kotlin.concurrent.thread

private val log = LoggerFactory.getLogger("Main") as Logger

fun getThreadCount(): Int = min(2, ForkJoinPool.getCommonPoolParallelism())

private val pool = Executors.newScheduledThreadPool(getThreadCount()) {
    thread(start=false, name="Worker-Thread", isDaemon=true, block=it::run)
}

private val poolScheduler = Schedulers.fromExecutor(pool)

fun main() {
    val configuration = loadConfiguration("config.json")
    val okhttp = OkHttpClient()
    val twitch = TwitchApi(okhttp, poolScheduler, configuration.twitchClientId)

    val manager = createManager {
        this.scheduler = poolScheduler
    }

    val jda = JDABuilder.createLight(configuration.token, GatewayIntent.GUILD_MESSAGES)
        .setEventManager(manager)
        .setHttpClient(okhttp)
        .setCallbackPool(pool)
        .setGatewayPool(pool)
        .setRateLimitPool(pool)
        .build()

    // Cycling streaming status
    val activityService = ActivityService(jda, poolScheduler)
    activityService.start()

    setupRankCreator(jda, configuration)
    setupRankListener(jda, configuration)
    // Optional message logging
    configuration.messageLogs?.let { messageWebhook ->
        MessageLogger(messageWebhook, pool, jda)
    }

    jda.awaitReady()

    val watchedStreams = mutableMapOf<String, StreamWatcher>()
    for (userLogin in configuration.twitchUser) {
        watchedStreams[userLogin] = StreamWatcher(twitch, jda, configuration, userLogin, pool, activityService)
    }

    startTwitchService(twitch, watchedStreams, poolScheduler)
}

private fun setupRankCreator(jda: JDA, configuration: Configuration) {
    val listener = Flux.merge(
        jda.on<GuildReadyEvent>().map(GuildReadyEvent::getGuild),
        jda.on<GuildJoinEvent>().map(GuildJoinEvent::getGuild)
    )

    val ranks = configuration.ranks.values
    listener
        .flatMap { guild ->
            ranks.toFlux()
                 .filter { guild.getRolesByName(it, true).isEmpty() }
                 .map { guild.createRole().setName(it) }
                 .flatMap(RoleAction::asMono)
        }
        .subscribe { log.info("Created role ${it.name} in ${it.guild.name}") }
}

private fun setupRankListener(jda: JDA, configuration: Configuration) {
    // Keep track of messages for cleanup when the invoking command is deleted
    data class MessagePath(val channel: Long, val message: Long)
    val messages = FixedSizeMap<Long, MessagePath>(10)

    jda.on<GuildMessageReceivedEvent>()
        .map { it.message }
        .filter { it.member != null }
        .filter { it.contentRaw.startsWith("?rank ") }
        .flatMap { message ->
            val member = message.member!!
            val mention = member.asMention
            // The role name is after the command
            val roleType = message.contentRaw.removePrefix("?rank ").toLowerCase()
            // We shouldn't let users assign themselves any other roles like mod
            val channel = message.channel
            Mono.defer {
                val roleName = configuration.ranks[roleType]
                    ?: return@defer channel.sendMessage("$mention, That role is not supported!").asMono()

                // Check if role by that name exists
                val role = message.guild.getRolesByName(roleName, true).firstOrNull()
                Mono.defer {
                    if (role != null) // Add/Remove the role to the member and send a success message
                        toggleRole(member, role, message, channel, mention)
                    else              // Send a failure message, unknown role
                        channel.sendMessage("$mention, That role does not exist!").asMono()
                }.onErrorResume(PermissionException::class.java) { error ->
                    log.error("Failed to execute rank command", error)
                    handlePermissionError(error, channel, mention, role)
                }
            }.doOnSuccess {
                messages[message.idLong] = MessagePath(channel.idLong, it.idLong)
            }
        }
        .doOnError { log.error("Rank service encountered exception", it) }
        .retry { it !is Error }
        .subscribe()

    jda.on<MessageDeleteEvent>()
       .map { it.messageIdLong }
       .filter(messages::containsKey)
       .map(messages::getValue)
       .flatMap {
           val (channelId, messageId) = it
           jda.getTextChannelById(channelId)
             ?.deleteMessageById(messageId)
             ?.asMono() ?: Mono.empty()
       }
       .subscribe()
}

private fun toggleRole(
    member: Member,
    role: Role,
    event: Message,
    channel: MessageChannel,
    mention: String
): Mono<Message> {
    return if (member.roles.any { it.idLong == role.idLong }) {
        log.debug("Adding ${role.name} to ${member.user.asTag}")
        event.guild.removeRoleFromMember(member, role).asMono().then {
            channel.sendMessage("$mention, you left **${role.name}**.").asMono()
        }
    } else {
        log.debug("Removing ${role.name} from ${member.user.asTag}")
        event.guild.addRoleToMember(member, role).asMono().then {
            channel.sendMessage("$mention, you joined **${role.name}**.").asMono()
        }
    }
}

private fun handlePermissionError(
    error: PermissionException,
    channel: MessageChannel,
    mention: String,
    role: Role?
): Mono<Message> {
    if (error.permission == Permission.MESSAGE_WRITE || error.permission == Permission.MESSAGE_READ)
        return Mono.empty() // Don't attempt to send another message if it already failed because of it
    return when (error) {
        is InsufficientPermissionException ->
            channel.sendMessage("$mention, I'm missing the permission **${error.permission.getName()}**").asMono()
        is HierarchyException ->
            channel.sendMessage("$mention, I can't assign a role to you because the role is too high! Role: ${role?.name}").asMono()
        else ->
            channel.sendMessage("$mention, encountered an error: `$error`!").asMono()
    }
}