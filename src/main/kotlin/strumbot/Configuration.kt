package strumbot

import net.dv8tion.jda.api.utils.data.DataObject
import java.io.File

data class Configuration(
    val token: String,
    val twitchClientId: String,
    val twitchClientSecret: String,
    val streamNotifications: String,
    val messageLogs: String?,
    val ranks: Map<String, String>,
    val events: Set<String>,
    val twitchUser: String
)


fun loadConfiguration(path: String): Configuration {
    val json = DataObject.fromJson(File(path).reader())
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
        twitch.getString("user_login")
    )
}