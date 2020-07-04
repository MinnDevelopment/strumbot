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

package strumbot

import club.minnced.jda.reactor.toMono
import net.dv8tion.jda.api.utils.data.DataArray
import net.dv8tion.jda.api.utils.data.DataObject
import okhttp3.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class HttpException(route: String, status: Int, meaning: String)
    : Exception("$route > $status: $meaning") {
    constructor(response: Response): this(response.request().url().toString(), response.code(), response.message())
}

private val emptyFormBody = RequestBody.create(MediaType.parse("form-data"), "")

fun createTwitchApi(http: OkHttpClient, scheduler: Scheduler, clientId: String, clientSecret: String): Mono<TwitchApi> = Mono.defer {
    val api = TwitchApi(http, scheduler, clientId, clientSecret, "N/A")
    api.authorize().thenReturn(api)
}

class NotAuthorizedError(
    response: Response
): Error("Authorization failed. Code: ${response.code()} Body: ${response.body()!!.string()}")

inline fun post(url: String, form: FormBody.Builder.() -> Unit): Request {
    val body = FormBody.Builder()
    body.form()
    return Request.Builder()
        .url(url)
        .method("POST", body.build())
        .build()
}

class TwitchApi(
    private val http: OkHttpClient,
    private val scheduler: Scheduler,
    private val clientId: String,
    private val clientSecret: String,
    private var accessToken: String) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(TwitchApi::class.java)
    }

    private val warnedMissingVod = mutableSetOf<String>()
    private val games = FixedSizeMap<String, Game>(10)

    internal fun authorize(): Mono<Unit> = Mono.create { sink ->
        val request = post("https://id.twitch.tv/oauth2/token") {
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("grant_type", "client_credentials")
        }

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                sink.error(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    when {
                        response.isSuccessful -> {
                            val json = DataObject.fromJson(response.body()!!.byteStream())
                            accessToken = json.getString("access_token")
                            sink.success()
                        }
                        response.code() < 500 -> sink.error(NotAuthorizedError(response))
                        else -> sink.error(HttpException(response))
                    }
                }
            }
        })
    }

    private fun <T> makeRequest(request: Request, failed: Boolean = false, handler: (Response) -> T?): Mono<T> = Mono.create<T> { sink ->
        log.trace("Making request to {}", request.url())
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                sink.error(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    log.trace("Got response {} for url {}", response.code(), request.url())
                    when {
                        failed && !response.isSuccessful -> {
                            sink.error(HttpException(response))
                        }
                        response.code() == 401 -> {
                            log.warn("Authorization expired, refreshing token...")
                            authorize()
                                .then(makeRequest(request, true, handler))
                                .subscribe(sink::success, sink::error, sink::success)
                        }
                        response.code() == 404 -> {
                            log.warn("Received 404 response for request to ${request.url()}")
                            sink.success()
                        }
                        response.code() == 429 -> {
                            log.warn("Hit rate limit, retrying request. Headers:\n{}", response.headers())
                            val reset = response.header("ratelimit-reset")?.let {
                                it.toLong() - System.currentTimeMillis()
                            } ?: 1000

                            Mono.delay(Duration.ofMillis(reset))
                                .then(makeRequest(request, true, handler))
                                .subscribe(sink::success, sink::error, sink::success)
                        }
                        response.isSuccessful -> sink.success(handler(response))
                        else -> sink.error(HttpException(response))
                    }
                }
            }
        })
    }.publishOn(scheduler)

    private fun newRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("Client-ID", clientId)
            .addHeader("Authorization", "Bearer $accessToken")
    }

    fun getStreamByLogin(login: Collection<String>): Mono<List<Stream>> = Mono.defer {
        val query = login.asSequence()
            .map { "user_login=$it" }
            .joinToString("&")
        val request = newRequest("https://api.twitch.tv/helix/streams?$query").build()

        makeRequest(request) { response ->
            val data = body(response)
            if (data.isEmpty) {
                emptyList()
            } else {
                List(data.length()) { i ->
                    val stream = data.getObject(i)
                    Stream(
                        stream.getString("id"),
                        stream.getString("game_id"),
                        stream.getString("title"),
                        stream.getString("type"),
                        stream.getString("language", "en"),
                        stream.getString("thumbnail_url"),
                        stream.getString("user_id"),
                        stream.getString("user_name"),
                        OffsetDateTime.parse(stream.getString("started_at"))
                    )
                }
            }
        }
    }

    fun getGame(stream: Stream): Mono<Game> = Mono.defer {
        if (stream.gameId.isEmpty()) {
            return@defer EMPTY_GAME.toMono()
        }
        if (stream.gameId in games) {
            return@defer games[stream.gameId].toMono()
        }

        val request = newRequest("https://api.twitch.tv/helix/games?id=${stream.gameId}").build()

        makeRequest(request) { response ->
            val data = body(response)
            if (data.isEmpty) {
                EMPTY_GAME
            } else {
                val game = data.getObject(0)
                games.computeIfAbsent(stream.gameId) {
                    Game(
                        game.getString("id"),
                        game.getString("name")
                    )
                }
            }
        }
    }

    fun getUserIdByLogin(login: String): Mono<String> = Mono.defer {
        val request = newRequest("https://api.twitch.tv/helix/users?login=$login").build()

        makeRequest(request) { response ->
            val data = body(response)
            if (data.isEmpty)
                null
            else
                data.getObject(0).getString("id")
        }
    }

    fun getVideoById(id: String, type: String? = "archive"): Mono<Video> = Mono.defer {
        val url = "https://api.twitch.tv/helix/videos?id=$id" + if (type != null) "&type=$type" else ""
        val request = newRequest(url).build()

        makeRequest(request) { response ->
            handleVideo(response)
        }
    }

    fun getVideoByStream(stream: Stream): Mono<Video> = Mono.defer {
        val userId = stream.userId
        val request = newRequest(
            "https://api.twitch.tv/helix/videos" +
                "?type=archive" + // archive = vod
                "&first=5" + // check 5 most recent videos, just in case it might ignore my type (default 20)
                "&user_id=$userId"
        ).build()

        makeRequest(request) { response ->
            val data = body(response)

            repeat(data.length()) { i ->
                val video = data.getObject(i)
                val type = video.getString("type")
                val createdAt = OffsetDateTime.parse(video.getString("created_at"))
                // Stream vods are always type archive (other types are highlight and upload)
                if (type == "archive" && !stream.startedAt.isAfter(createdAt)) {
                    return@makeRequest buildVideo(video)
                }
            }
            if (warnedMissingVod.add(stream.userLogin))
                log.warn("Could not find vod for current stream by ${stream.userLogin}. Did you enable archives?")
            return@makeRequest null
        }
    }

    fun getTopClips(userId: String, startedAt: Long, num: Int = 5): Mono<List<Video>> {
        val request = newRequest(
            "https://api.twitch.tv/helix/clips" +
                "?broadcaster_id=$userId" +
                "&first=$num" +
                "&started_at=${Instant.ofEpochSecond(startedAt)}"
        ).build()

        return makeRequest(request) { response ->
            val data = body(response)
            if (data.length() == 0)
                emptyList()
            else {
                List(data.length()) {
                    buildVideo(data.getObject(it))
                }
            }
        }
    }

    fun getThumbnail(stream: Stream, width: Int = 1920, height: Int = 1080): Mono<InputStream> = getThumbnail(stream.thumbnail, width, height)
    fun getThumbnail(video: Video, width: Int = 1920, height: Int = 1080): Mono<InputStream> = getThumbnail(video.thumbnail, width, height)
    fun getThumbnail(url: String, width: Int, height: Int): Mono<InputStream> = Mono.defer {
        // Stream url uses {width} and video url uses %{width} ??????????????? OK TWITCH ???????????
        val thumbnailUrl = url.replace(Regex("%?\\{width}"), width.toString())
                              .replace(Regex("%?\\{height}"), height.toString()) + "?v=${System.currentTimeMillis()}" // add random number to avoid cache!
        val request = Request.Builder()
            .url(thumbnailUrl)
            .build()

        makeRequest(request) { response ->
            val buffer = ByteArrayOutputStream()
            response.body()!!.byteStream().copyTo(buffer)
            ByteArrayInputStream(buffer.toByteArray())
        }
    }

    private fun handleVideo(response: Response): Video? {
        val data = body(response)
        return if (data.isEmpty)
            null
        else {
            val video = data.getObject(0)
            buildVideo(video)
        }
    }

    private fun body(response: Response): DataArray {
        val json = DataObject.fromJson(response.body()!!.byteStream())
        return json.getArray("data")
    }

    private fun buildVideo(video: DataObject): Video {
        val id = video.getString("id")
        val url = video.getString("url")
        val title = video.getString("title")
        val thumbnail = video.getString("thumbnail_url")
        val views = video.getInt("view_count", 0)
        return Video(id, url, title, thumbnail, views)
    }
}

data class Stream(
    val streamId: String,
    val gameId: String,
    val title: String,
    val type: String,
    val language: String,
    val thumbnail: String,
    val userId: String,
    val userLogin: String,
    val startedAt: OffsetDateTime)
data class Video(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String,
    val views: Int)
data class Game(val gameId: String, val name: String)

val EMPTY_GAME = Game("", "No Category")