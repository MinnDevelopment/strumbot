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
@file:JvmName("WebhookAppender")
package strumbot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.AppenderBase
import dev.minn.jda.ktx.Embed
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.api.requests.restaction.WebhookMessageAction
import java.time.Instant

class WebhookAppender : AppenderBase<LoggingEvent>() {
    companion object {
        private var client: WebhookClient<WebhookMessageAction>? = null

        fun init(api: JDA, url: String) {
            client = WebhookClient.createClient(api, url)
        }
    }

    override fun append(eventObject: LoggingEvent?) {
        if (eventObject == null || !eventObject.level.isGreaterOrEqual(Level.WARN)) return
        val embed = Embed {
            description = eventObject.formattedMessage
            eventObject.throwableProxy?.let {
                description += "```\n$it\n```"
            }
            when (eventObject.level.toInt()) {
                Level.ERROR_INT -> color = 0xFF0000
                Level.WARN_INT -> color = 0xF8F8FF
            }

            timestamp = Instant.ofEpochMilli(eventObject.timeStamp)
        }

        client?.sendMessage(embed)
              ?.setUsername("Error Log")
              ?.queue()
    }
}