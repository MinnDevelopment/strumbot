package strumbot

import net.dv8tion.jda.api.utils.data.DataObject
import java.io.File

data class Configuration(
    val token: String,
    val twitchClientId: String,
    val twitchClientSecret: String,
    val webhookUrl: String,
    val ranks: List<String>
)


fun loadConfiguration(path: String): Configuration {
    val json = DataObject.fromJson(File(path).reader())
    val discord = json.getObject("discord")
    val twitch = json.getObject("twitch")

    return Configuration(
        discord.getString("token"),
        twitch.getString("client_id"),
        twitch.getString("client_secret"),
        discord.getString("webhook"),
        discord.getArray("ranks").toList().map(Any::toString)
    )
}