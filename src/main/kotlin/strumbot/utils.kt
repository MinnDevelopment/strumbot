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

import dev.minn.jda.ktx.scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import kotlin.time.Duration

// Convert role type to role id
private val rankByType: MutableMap<String, String> = mutableMapOf()

fun filterId(guild: Guild, id: Long) = guild.idLong == id || id == 0L

fun JDA.getRoleByType(config: DiscordConfig, type: String): String {
    val roleName = config.ranks[type] ?: "0"
    if (type !in rankByType) {
        if (roleName.isEmpty()) {
            rankByType[type] = "0"
        } else {
            // Find role by name
            val roleId = getRolesByName(roleName, true)
                .firstOrNull { filterId(it.guild, config.guildId) } // filter by server id (if applicable)
                ?.id ?: return "0" // take id or return "0" as fallback
            rankByType[type] = roleId
        }
    }
    return rankByType[type] ?: "0"
}

inline fun JDA.repeatUntilShutdown(rate: Duration, initDelay: Duration = rate, crossinline task: suspend CoroutineScope.() -> Unit): Job {
    return scope.launch {
        delay(initDelay)
        while (status != JDA.Status.SHUTDOWN) {
            task()
            delay(rate)
        }
    }
}
