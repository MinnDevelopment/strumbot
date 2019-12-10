package strumbot

import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.send.WebhookEmbed.*
import club.minnced.discord.webhook.send.WebhookEmbedBuilder
import club.minnced.jda.reactor.on
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.concurrent.ScheduledExecutorService

data class DiscordMessage(
    val userId: String,
    val userTag: String,
    val content: String
)

const val EDIT_COLOR = 0x7289DA
const val DELETE_COLOR = 0xFF0000

class MessageLogger(
    private val webhookUrl: String, private val pool: ScheduledExecutorService,
    private val jda: JDA) {

    private val messageCache = FixedSizeMap<Long, DiscordMessage>(50)
    private val webhook: WebhookClient by lazy {
        WebhookClientBuilder(webhookUrl)
            .setHttpClient(jda.httpClient)
            .setExecutorService(pool)
            .setWait(false)
            .build()
    }

    init {
        jda.on<MessageReceivedEvent>()
           .filter { it.author != jda.selfUser }
           .subscribe {
               val message = DiscordMessage(
                   it.author.id,
                   it.author.asTag,
                   it.message.contentRaw
               )

               messageCache[it.messageIdLong] = message
           }

        jda.on<MessageUpdateEvent>()
           .filter { it.messageIdLong in messageCache }
           .filter { it.message.timeEdited != null }
           .filter { it.message.contentRaw != messageCache[it.messageIdLong]!!.content }
           .flatMap {
               val oldMessage = messageCache[it.messageIdLong]!!
               val nesMessage = it.message
               val embed = WebhookEmbedBuilder()
                   .addField(EmbedField(
                       false, "Old Content",
                       oldMessage.content
                   ))
                   .addField(EmbedField(
                       false, "New Content",
                       nesMessage.contentRaw
                   ))
                   .setColor(EDIT_COLOR)
                   .setAuthor(EmbedAuthor("Message Edited ${oldMessage.userTag} (${oldMessage.userId})", null, null))
                   .setTimestamp(nesMessage.timeEdited)
                   .setFooter(EmbedFooter("#${it.channel.name}", null))

               messageCache[it.messageIdLong] = DiscordMessage(
                   it.author.id,
                   it.author.asTag,
                   nesMessage.contentRaw
               )
               Mono.fromFuture { webhook.send(embed.build()) }
           }
           .subscribe()

        jda.on<MessageDeleteEvent>()
           .flatMap {
               val oldMessage = messageCache[it.messageIdLong]

               val embed = WebhookEmbedBuilder()
                   .setTimestamp(OffsetDateTime.now())
                   .setColor(DELETE_COLOR)
                   .setFooter(EmbedFooter("#${it.channel.name}", null))

               if (oldMessage != null) {
                   embed.setDescription(oldMessage.content)
                   embed.setAuthor(EmbedAuthor(
                       "Message Deleted from ${oldMessage.userTag} (${oldMessage.userId})", null, null
                   ))
               } else {
                   embed.setTitle(EmbedTitle("Message Deleted", null))
                   embed.setDescription("Unknown Content (too old)")
               }

               Mono.fromFuture { webhook.send(embed.build()) }
           }
           .subscribe()
    }
}