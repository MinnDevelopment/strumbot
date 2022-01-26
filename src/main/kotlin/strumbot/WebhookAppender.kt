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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.WebhookClient

class WebhookAppender : AppenderBase<LoggingEvent>() {
    companion object {
        lateinit var instance: WebhookAppender
        private var client: WebhookClient<*>? = null
        private val buffer = StringBuilder(2000)

        fun init(api: JDA, url: String, scope: CoroutineScope) {
            client = url.asWebhook(api)
            scope.launch {
                while (true) {
                    delay(500)
                    send()
                }
            }
        }

        fun send() = synchronized(buffer) {
            if (buffer.isEmpty()) return@synchronized
            val message = "```ansi\n${buffer}\n```"
            buffer.setLength(0)
            client?.sendMessage(message)?.queue(null) { it.printStackTrace() }
        }
    }

    var level: String? = null
    lateinit var encoder: PatternLayoutEncoder

    private val minLevel: Level get() = Level.toLevel(level ?: "warn")

    override fun start() {
        if (!::encoder.isInitialized)
            throw AssertionError("Missing pattern encoder")

        instance = this
        encoder.start()
        super.start()
    }

    override fun append(event: LoggingEvent) {
        if (!event.level.isGreaterOrEqual(minLevel)) return
        synchronized(buffer) {
            buffer.append(encoder.encode(event).toString(Charsets.UTF_8).take(1500))
            if (buffer.length > 1000)
                send()
        }
    }
}