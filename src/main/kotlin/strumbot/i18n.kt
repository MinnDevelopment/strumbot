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

import java.util.*

class LocalizationManager {
    private val translations: MutableMap<Locale, Properties> = mutableMapOf()

    fun getText(locale: Locale, name: String): String {
        if (locale !in translations) {
            val path = "/strumbot/${locale.language}.properties"
            println("Loading resource $path")
            val resource = javaClass.getResourceAsStream(path)
            if (resource == null) {
                if (locale.language == "en")
                    throw IllegalStateException("Could not load default resource for english!")
                return getText(Locale.forLanguageTag("en"), name)
            }
            translations[locale] = Properties().apply { load(resource) }
        }

        return translations.getValue(locale).getProperty(name)
    }
}

val localizationManager: LocalizationManager = LocalizationManager()

fun getLocale(stream: Stream): Locale = Locale.forLanguageTag(stream.language) ?: Locale.forLanguageTag("en")

fun getText(locale: Locale, name: String): String {
    return localizationManager.getText(locale, name)
}

fun getText(locale: Locale, name: String, tokens: Map<String, Any?>): String {
    var template = getText(locale, name)
    tokens.forEach { (key, value) ->
        template = template.replace("{{$key}}", value.toString())
    }

    return template
}

fun getText(locale: Locale, name: String, vararg tokens: Pair<String, Any?>): String {
    return getText(locale, name, mapOf(*tokens))
}
