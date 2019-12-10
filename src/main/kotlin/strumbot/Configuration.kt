package strumbot

import net.dv8tion.jda.api.utils.data.DataObject
import java.io.File

data class Configuration(
    val token: String,
    val twitchClientId: String,
    val twitchClientSecret: String,
    val streamNotifications: String,
    val messageLogs: String?,
    val ranks: List<String>,
    val twitchUser: String
)


fun loadConfiguration(path: String): Configuration {
    val json = DataObject.fromJson(File(path).reader())
    val discord = json.getObject("discord")
    val twitch = json.getObject("twitch")

    return Configuration(
        discord.getString("token"),
        twitch.getString("client_id"),
        twitch.getString("client_secret"),
        discord.getString("stream_notifications"),
        discord.getString("message_logs", null),
        discord.getArray("ranks").toList().map(Any::toString),
        twitch.getString("user_login")
    )
}