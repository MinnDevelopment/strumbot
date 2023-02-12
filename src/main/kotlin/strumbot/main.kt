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

import ch.qos.logback.classic.PatternLayout
import dev.minn.jda.ktx.CoroutineEventManager
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.interactions.choice
import dev.minn.jda.ktx.interactions.command
import dev.minn.jda.ktx.interactions.option
import dev.minn.jda.ktx.interactions.updateCommands
import dev.minn.jda.ktx.light
import dev.minn.jda.ktx.onCommand
import kotlinx.coroutines.*
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.ShutdownEvent
import net.dv8tion.jda.api.events.guild.GenericGuildEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.exceptions.HierarchyException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.utils.AllowedMentions
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.Integer.max
import java.util.*
import java.util.concurrent.CancellationException
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

fun main() {
    RestAction.setDefaultTimeout(10, TimeUnit.SECONDS)
    AllowedMentions.setDefaultMentions(EnumSet.of(Message.MentionType.ROLE))

    val config = loadConfiguration("config.json")
    val okhttp = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 20, TimeUnit.SECONDS))
        .build()

    // Use the global thread pool for coroutine dispatches
    val dispatcher = pool.asCoroutineDispatcher()
    // Using a SupervisorJob allows coroutines to fail without cancelling all other jobs
    val supervisor = SupervisorJob()
    // Implement a logging exception handler for uncaught throws in launched jobs
    val handler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException)
            log.error("Uncaught exception in coroutine", throwable)
        if (throwable is Error) {
            supervisor.cancel()
            throw throwable
        }
    }

    // Create our coroutine scope
    val context = dispatcher + supervisor + handler
    val scope = CoroutineScope(context)

    // Create a coroutine manager with this scope and a default event timeout of 1 minute
    val manager = CoroutineEventManager(scope, 1.minutes)
    manager.initCommands(config.discord)
    manager.initRoles(config.discord)

    manager.listener<ShutdownEvent> {
        supervisor.cancel()
    }

    log.info("Initializing twitch api")
    val twitch: TwitchApi = runBlocking {
        createTwitchApi(okhttp, config.twitch.clientId, config.twitch.clientSecret, scope)
    }

    log.info("Initializing discord connection")
    val jda = light(config.discord.token, enableCoroutines=false, intents=emptyList()) {
        setEventManager(manager)
        setHttpClient(okhttp)
        setCallbackPool(pool)
        setGatewayPool(pool)
        setRateLimitPool(pool)
    }

    config.discord.logging?.let { cfg ->
        config.logger.level?.let {
            WebhookAppender.instance.level = it
        }
        config.logger.pattern?.let {
            val layout = WebhookAppender.instance.encoder.layout as PatternLayout
            layout.pattern = it
            layout.start()
        }

        WebhookAppender.init(jda, cfg, scope)
    }

    // Cycling streaming status
    val activityService = ActivityService(jda)
    activityService.start()

    // Handle rank command
    setupRankListener(jda, config.discord)
    // Wait for cache to finish initializing
    jda.awaitReady()

    val watchedStreams = mutableMapOf<String, StreamWatcher>()
    for (userLogin in config.twitch.userNames) {
        val key = userLogin.lowercase(Locale.ROOT) // make sure we don't insert things twice
        watchedStreams[key] = StreamWatcher(twitch, jda, config, userLogin, activityService)
    }

    val twitchJob = startTwitchService(twitch, jda, watchedStreams)

    twitchJob.invokeOnCompletion {
        if (it != null && it !is CancellationException) {
            log.error("Twitch service terminated unexpectedly", it)
            supervisor.cancel()
        }
    }

    supervisor.invokeOnCompletion {
        if (it != null && it !is CancellationException) {
            log.error("Supervisor failed with unexpected error", it)
        } else {
            log.info("Shutting down")
        }

        jda.shutdown()
    }

    System.gc()
}

/**
 * Creates the roles which are mentioned for webhook notifications
 */
private fun CoroutineEventManager.initRoles(config: DiscordConfig) = listener<GenericGuildEvent> { event ->
    if (event !is GuildReadyEvent && event !is GuildJoinEvent) return@listener
    val guild = event.guild

    if (!filterId(guild, config.guildId)) return@listener

    config.ranks.values
        .asSequence()
        .filter(String::isNotEmpty)
        .filter { guild.getRolesByName(it, true).isEmpty() }
        .map { guild.createRole().setName(it) }
        .forEach {
            val role = it.await()
            log.info("Created role {} in {}", role.name, guild.name)
        }
}

/**
 * Creates the relevant commands for role management
 */
private fun CoroutineEventManager.initCommands(config: DiscordConfig) = listener<GenericGuildEvent> { event ->
    if (event !is GuildReadyEvent && event !is GuildJoinEvent) return@listener
    val guild = event.guild

    if (!filterId(guild, config.guildId)) return@listener

    if (config.ranks.all { it.value.isBlank() }) {
        guild.updateCommands().queue()
    } else {
        guild.updateCommands {
            command("notify", "Add or remove one of the notification roles") {
                option<String>("role", "The role to assign or remove you from", required = true) {
                    config.ranks.forEach { (type, name) ->
                        if (name.isNotBlank())
                            choice(name, type)
                    }
                }
            }
        }.queue()
    }
}

/**
 * Handles the rank command
 */
private fun setupRankListener(jda: JDA, config: DiscordConfig) = jda.onCommand("notify") { event ->
    val guild = event.guild ?: return@onCommand
    val member = event.member ?: return@onCommand

    // Get the role instance for the requested rank
    val type = event.getOption("role")?.asString ?: ""
    val role = guild.getRoleById(jda.getRoleByType(config, type)) ?: run {
        event.reply("Role for `$type` event could not be identified.").queue()
        return@onCommand
    }

    event.deferReply(true).queue() // This is required to handle delayed response
    event.hook.setEphemeral(true) // Make messages only visible to command user

    try {
        val added = member.toggleRole(role)
        event.hook.sendMessage(
            if (added) "Added the role"
            else       "Removed the role"
        ).await()
    } catch (ex: PermissionException) {
        // If there is a permission issue, handle it by telling the user about the problem
        event.hook.sendMessage(handlePermissionError(ex, role)).await()
        log.error("Failed to add or remove role for a member. Member: {} ({}) Role: {} ({})",
                  member.user.asTag, member.id, role.name, role.id, ex)
    }
}

private suspend fun Member.toggleRole(
    role: Role
) = if (role in roles) {
    log.debug("Removing {} from {}", role.name, user.asTag)
    role.guild.removeRoleFromMember(this, role).await()
    false
} else {
    log.debug("Adding {} to {}", role.name, user.asTag)
    role.guild.addRoleToMember(this, role).await()
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
