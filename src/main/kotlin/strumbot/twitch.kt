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
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.OffsetDateTime

class HttpException(route: String, status: Int, meaning: String)
    : Exception("$route > $status: $meaning") {
    constructor(response: Response): this(response.request().url().toString(), response.code(), response.message())
}

private val emptyFormBody = RequestBody.create(MediaType.parse("form-data"), "")

fun createTwitchApi(http: OkHttpClient, scheduler: Scheduler, clientId: String, clientSecret: String): Mono<TwitchApi> = Mono.create { sink ->
    val request = Request.Builder()
        .url("https://id.twitch.tv/oauth2/token" +
                "?client_id=${clientId}" +
                "&client_secret=${clientSecret}" +
                "&grant_type=client_credentials")
        .method("POST", emptyFormBody)
        .build()

    http.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            sink.error(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful) {
                sink.error(HttpException(response))
                return
            }

            val json = DataObject.fromJson(response.body()!!.byteStream())
            val accessToken = json.getString("access_token")
            sink.success(TwitchApi(
                http, scheduler,
                clientId, accessToken))
        }
    })
}

class TwitchApi(
    private val http: OkHttpClient,
    private val scheduler: Scheduler,
    private val clientId: String,
    private var accessToken: String) {

    private val games = FixedSizeMap<String, Game>(10)

    private fun <T> makeRequest(request: Request, handler: (Response) -> T?): Mono<T> = Mono.create<T> { sink ->
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                sink.error(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        sink.success(handler(response))
                    } else {
                        sink.error(HttpException(response))
                    }
                }
            }
        })
    }.publishOn(scheduler)

    private fun newRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("Client-ID", clientId)
            .addHeader("Authorization", "OAuth $accessToken")
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
        if (stream.gameId in games) {
            return@defer games[stream.gameId].toMono()
        }

        val request = newRequest("https://api.twitch.tv/helix/games?id=${stream.gameId}").build()

        makeRequest(request) { response ->
            val data = body(response)
            if (data.isEmpty) {
                null
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
            return@makeRequest null
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
        return Video(id, url, title, thumbnail)
    }
}

data class Stream(
    val streamId: String,
    val gameId: String,
    val title: String,
    val type: String,
    val thumbnail: String,
    val userId: String,
    val userLogin: String,
    val startedAt: OffsetDateTime)
data class Video(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String)
data class Game(val gameId: String, val name: String)
