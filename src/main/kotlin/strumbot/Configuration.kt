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

import net.dv8tion.jda.api.utils.data.DataObject
import net.dv8tion.jda.api.utils.data.DataType
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.max
import kotlin.math.min

data class TwitchConfig(
    /** Client ID of twitch application */
    val clientId: String,
    /** Client secret of twitch application */
    val clientSecret: String,
    /** The twitch streamer names to keep track off */
    val userNames: Set<String>,
    /** How many top clips to show in the VOD event (0-5) */
    val topClips: Int,
)

data class DiscordConfig(
    /** The discord bot token used to handle role assignments */
    val token: String,
    /** Logger webhook URL */
    val logging: String?,
    /** The webhook url used for stream notifications */
    val notifications: String,
    /** Whether to add a footer to stream notifications with command hints */
    val notifyHint: Boolean,
    /** The guild in which to handle commands */
    val guildId: Long,
    /** The role names used for notification mentions */
    val ranks: Map<String, String>,
    /** Which events to enable for notifications */
    val events: Set<String>,
)

data class LoggerConfig(
    /** The logging level used for webhook logging */
    val level: String?,
    /** The logging pattern for webhook logging */
    val pattern: String?,
)

data class Configuration(
    val discord: DiscordConfig,
    val twitch: TwitchConfig,
    val logger: LoggerConfig
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
    val roles = discord.getObject("role_name").run {
        mapOf(
            "live" to getString("live", ""),
            "update" to getString("update", ""),
            "vod" to getString("vod", "")
        )
    }
    val events = discord.getArray("enabled_events").map(Any::toString).toSet()
    val userLogin = if (twitch.isType("user_login", DataType.ARRAY))
                        twitch.getArray("user_login").map(Any::toString).toSet()
                    else
                        setOf(twitch.getString("user_login"))

    val logging = json.optObject("logger").orElseGet(DataObject::empty)

    val discordConfig = DiscordConfig(
        discord.getString("token"),
        discord.getString("logging", null),
        discord.getString("stream_notifications"),
        discord.getBoolean("show_notify_hints", false),
        discord.getUnsignedLong("server_id", 0L),
        roles, events
    )

    val twitchConfig = TwitchConfig(
        twitch.getString("client_id"),
        twitch.getString("client_secret"),
        userLogin,
        min(5, max(0, twitch.getInt("top_clips", 0))),
    )

    val loggingConfig = LoggerConfig(
        logging.getString("level", null),
        logging.getString("pattern", null),
    )

    return Configuration(discordConfig, twitchConfig, loggingConfig)
}