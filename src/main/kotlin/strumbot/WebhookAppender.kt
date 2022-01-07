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
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.pattern.color.ANSIConstants
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

    private object Color {
        private fun csi(const: String, mod: String = "") = "${ANSIConstants.ESC_START}$mod$const${ANSIConstants.ESC_END}"

        val RESET = csi(ANSIConstants.DEFAULT_FG)

        val RED = csi(ANSIConstants.RED_FG)
        val MAGENTA = csi(ANSIConstants.MAGENTA_FG)
        val BLUE = csi(ANSIConstants.BLUE_FG)
        val CYAN = csi(ANSIConstants.CYAN_FG)
        val WHITE = csi(ANSIConstants.WHITE_FG)

        val BOLD_RED = csi(ANSIConstants.RED_FG, ANSIConstants.BOLD)
        val BOLD_MAGENTA = csi(ANSIConstants.MAGENTA_FG, ANSIConstants.BOLD)
        val BOLD_BLUE = csi(ANSIConstants.BLUE_FG, ANSIConstants.BOLD)
        val BOLD_CYAN = csi(ANSIConstants.CYAN_FG, ANSIConstants.BOLD)
        val BOLD_WHITE = csi(ANSIConstants.WHITE_FG, ANSIConstants.BOLD)
    }

    private fun highlight(level: Level) = when (level) {
        Level.ERROR -> "${Color.BOLD_RED}${level.levelStr}${Color.RESET}"
        Level.WARN -> "${Color.MAGENTA}${level.levelStr}${Color.RESET}"
        Level.INFO -> "${Color.CYAN}${level.levelStr}${Color.RESET}"
        Level.DEBUG -> "${Color.BLUE}${level.levelStr}${Color.RESET}"
        Level.TRACE -> "${Color.WHITE}${level.levelStr}${Color.RESET}"
        else -> level.levelStr
    }

    override fun append(eventObject: LoggingEvent) {
        if (!eventObject.level.isGreaterOrEqual(Level.WARN)) return
        val message = StringBuilder()

        message.append("```ansi\n[")
        message.append(highlight(eventObject.level))
        message.append("] ")
        message.append(eventObject.formattedMessage)

        eventObject.throwableProxy?.let { throwable ->
            message.append("\n")
            message.append(Color.RED)
            message.append(throwable.className)
            message.append(": ")
            message.append(throwable.message)
            var done = true
            for (elem in throwable.stackTraceElementProxyArray) {
                val traceLine = elem.steAsString
                if (traceLine.length + message.length >= 1500) {
                    done = false
                    break
                }
                message.append("\n\t")
                message.append(traceLine)
            }
            if (!done)
                message.append("\n\t...")
            message.append(Color.RESET)
        }

        message.append("\n```")

        val embed = Embed(
            description = message.toString(),
            timestamp = Instant.ofEpochMilli(eventObject.timeStamp)
        )
        client?.sendMessageEmbeds(embed)?.queue(null) { it.printStackTrace() }
    }
}