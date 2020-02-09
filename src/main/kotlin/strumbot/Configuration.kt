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

package strumbot

import net.dv8tion.jda.api.utils.data.DataArray
import net.dv8tion.jda.api.utils.data.DataObject
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException

data class Configuration(
    val token: String,
    val twitchClientId: String,
    val twitchClientSecret: String,
    val streamNotifications: String,
    val messageLogs: String?,
    val ranks: Map<String, String>,
    val events: Set<String>,
    val twitchUser: Set<String>
)

private val log = LoggerFactory.getLogger(Configuration::class.java)

fun loadConfiguration(path: String, fallback: String = "/etc/strumbot/config.json"): Configuration {
    var file: File
    val json = try {
        file = File(path)
        DataObject.fromJson(file.reader())
    } catch (ex: FileNotFoundException) {
        file = File(fallback)
        DataObject.fromJson(file.reader())
    }

    log.info("Loaded config from ${file.canonicalPath}")

    val discord = json.getObject("discord")
    val twitch = json.getObject("twitch")
    val roles = discord.getObject("role_name").let {
        val map = mutableMapOf<String, String>()
        map["live"] = it.getString("live", "")
        map["update"] = it.getString("update", "")
        map["vod"] = it.getString("vod", "")
        map
    }
    val events = discord.getArray("enabled_events").toList().map { it.toString() }.toSet()

    return Configuration(
        discord.getString("token"),
        twitch.getString("client_id"),
        twitch.getString("client_secret"),
        discord.getString("stream_notifications"),
        discord.getString("message_logs", null),
        roles,
        events,
        twitch.optArray("user_login").orElseGet {
            DataArray.empty()
                .add(twitch.getString("user_login"))
        }.map(Any::toString).toSet()
    )
}