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

package strumbot

import club.minnced.jda.reactor.on
import dev.minn.jda.ktx.scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent
import net.dv8tion.jda.api.requests.RestAction
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuple2
import reactor.util.function.Tuple3
import reactor.util.function.Tuple4
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

operator fun <T> Tuple2<T, *>.component1(): T = t1
operator fun <T> Tuple2<*, T>.component2(): T = t2

operator fun <T> Tuple3<T, *, *>.component1(): T = t1
operator fun <T> Tuple3<*, T, *>.component2(): T = t2
operator fun <T> Tuple3<*, *, T>.component3(): T = t3

operator fun <T> Tuple4<T, *, *, *>.component1(): T = t1
operator fun <T> Tuple4<*, T, *, *>.component2(): T = t2
operator fun <T> Tuple4<*, *, T, *>.component3(): T = t3
operator fun <T> Tuple4<*, *, *, T>.component4(): T = t4

// Convert role type to role id
private val rankByType: MutableMap<String, String> = mutableMapOf()

fun filterId(guild: Guild, id: Long) = guild.idLong == id || id == 0L

fun JDA.getRoleByType(configuration: Configuration, type: String): String {
    val roleName = configuration.ranks[type] ?: "0"
    if (type !in rankByType) {
        // Find role by name
        val roleId = getRolesByName(roleName, true)
            .firstOrNull { filterId(it.guild, configuration.guildId) } // filter by server id (if applicable)
            ?.id ?: return "0" // take id or return "0" as fallback
        rankByType[type] = roleId
    }
    return rankByType[type] ?: "0"
}

suspend fun <T> RestAction<T>.await() = suspendCoroutine<T> { cont ->
    queue(cont::resume, cont::resumeWithException)
}

fun JDA.onCommand(name: String): Flux<SlashCommandEvent> = on<SlashCommandEvent>().filter { it.name == name }

fun <T> Mono<T>.ignoreFailure(): Mono<T> =
    doOnError { LoggerFactory.getLogger(StreamWatcher::class.java).error("Could not fetch video.", it) }
      .onErrorResume(HttpException::class.java) { Mono.empty() }
fun <T> Flux<T>.ignoreFailure(): Flux<T> =
    doOnError { LoggerFactory.getLogger(StreamWatcher::class.java).error("Could not fetch video.", it) }
        .onErrorResume(HttpException::class.java) { Mono.empty() }

suspend fun <T> Mono<T>.await(): T? = ignoreFailure().awaitFirstOrNull()
suspend fun <T> Flux<T>.await(): T? = ignoreFailure().awaitFirstOrNull()

inline fun JDA.repeatUntilShutdown(rate: Duration, initDelay: Duration = rate, crossinline task: suspend CoroutineScope.() -> Unit): Job {
    return scope.launch {
        delay(initDelay)
        while (status != JDA.Status.SHUTDOWN) {
            task()
            delay(rate)
        }
    }
}