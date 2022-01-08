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
@file:JvmName("WebhookAppender")
package strumbot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.AppenderBase
import dev.minn.jda.ktx.Embed
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.WebhookClient
import java.time.Instant

class WebhookAppender : AppenderBase<LoggingEvent>() {
    companion object {
        private var client: WebhookClient<*>? = null

        fun init(api: JDA, url: String) {
            client = url.asWebhook(api)
        }
    }

    lateinit var encoder: PatternLayoutEncoder

    override fun start() {
        if (!::encoder.isInitialized)
            throw AssertionError("Missing pattern encoder")

        encoder.start()
        super.start()
    }

    override fun append(event: LoggingEvent) {
        if (!event.level.isGreaterOrEqual(Level.WARN)) return
        val message = StringBuilder()

        message.append("```ansi\n")
        message.append(encoder.encode(event).toString(Charsets.UTF_8).take(1500))
        message.append("\n```")

        val embed = Embed(
            description = message.toString(),
            timestamp = Instant.ofEpochMilli(event.timeStamp)
        )
        client?.sendMessageEmbeds(embed)?.queue(null) { it.printStackTrace() }
    }
}