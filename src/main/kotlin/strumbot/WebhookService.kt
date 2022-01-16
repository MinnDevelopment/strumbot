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

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Webhook
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.internal.JDAImpl
import net.dv8tion.jda.internal.entities.AbstractWebhookClient
import net.dv8tion.jda.internal.requests.Route
import net.dv8tion.jda.internal.requests.restaction.WebhookMessageActionImpl
import net.dv8tion.jda.internal.requests.restaction.WebhookMessageUpdateActionImpl
import java.util.regex.Matcher

fun String.asWebhook(api: JDA)  = Webhook.WEBHOOK_URL.matcher(this).apply(Matcher::matches).run {
    WebhookService(api, group("id").toLong(), group("token"))
}

class WebhookService(api: JDA, id: Long, token: String) : WebhookClient<Message>, AbstractWebhookClient<Message>(id, token, api) {
    override fun sendRequest(): WebhookMessageActionImpl<Message> {
        val route = Route.Webhooks.EXECUTE_WEBHOOK.compile(id.toString(), token).run { withQueryParams("wait", "true") }
        return WebhookMessageActionImpl(api, null, route) {
            (api as JDAImpl).entityBuilder.createMessage(it)
        }.apply { run() }
    }

    override fun editRequest(messageId: String?): WebhookMessageUpdateActionImpl<Message> {
        val route = Route.Webhooks.EXECUTE_WEBHOOK_EDIT.compile(id.toString(), token).run { withQueryParams("wait", "true") }
        return WebhookMessageUpdateActionImpl(api, route) {
            (api as JDAImpl).entityBuilder.createMessage(it)
        }.apply { run() }
    }
}