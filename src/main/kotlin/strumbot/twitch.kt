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
    : Exception("$route > $status: $meaning")

class TwitchApi(
    private val http: OkHttpClient,
    private val scheduler: Scheduler,
    private val clientId: String,
    private val clientSecret: String) {

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
                        sink.error(HttpException(
                            response.request().url().toString(),
                            response.code(),
                            response.message()
                        ))
                    }
                }
            }
        })
    }.publishOn(scheduler)

    fun getStreamByLogin(login: Collection<String>): Mono<List<Stream>> = Mono.defer<List<Stream>> {
        val query = login.asSequence()
            .map { "user_login=$it" }
            .joinToString("&")
        val request = Request.Builder()
            .addHeader("Client-ID", clientId)
            .url("https://api.twitch.tv/helix/streams?$query")
            .build()

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

    fun getGame(stream: Stream): Mono<Game> = Mono.defer<Game> {
        if (stream.gameId in games) {
            return@defer games[stream.gameId].toMono()
        }

        val request = Request.Builder()
            .addHeader("Client-ID", clientId)
            .url("https://api.twitch.tv/helix/games?id=${stream.gameId}")
            .build()

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

    fun getUserIdByLogin(login: String): Mono<String> = Mono.defer<String> {
        val request = Request.Builder()
            .addHeader("Client-Id", clientId)
            .url("https://api.twitch.tv/helix/users?login=$login")
            .build()

        makeRequest(request) { response ->
            val data = body(response)
            if (data.isEmpty)
                null
            else
                data.getObject(0).getString("id")
        }
    }

    fun getVideoById(id: String): Mono<Video> = Mono.defer<Video> {
        val request = Request.Builder()
            .addHeader("Client-Id", clientId)
            .url("https://api.twitch.tv/helix/videos?id=$id")
            .build()

        makeRequest(request) { response ->
            handleVideo(response)
        }
    }

    fun getVideoByStream(stream: Stream): Mono<Video> = Mono.defer<Video> {
        val userId = stream.userId
        val request = Request.Builder()
            .addHeader("Client-Id", clientId)
            .url("https://api.twitch.tv/helix/videos?user_id=$userId")
            .build()

        makeRequest(request) { response ->
            val data = body(response)

            repeat(data.length()) { i ->
                val video = data.getObject(i)
                val type = video.getString("type")
                // Stream vods are always type archive (other types are highlight and upload)
                if (type == "archive") {
                    return@makeRequest buildVideo(video)
                }
            }
            return@makeRequest null
        }
    }

    fun getThumbnail(stream: Stream, width: Int = 1920, height: Int = 1080): Mono<InputStream> = getThumbnail(stream.thumbnail, width, height)
    fun getThumbnail(video: Video, width: Int = 1920, height: Int = 1080): Mono<InputStream> = getThumbnail(video.thumbnail, width, height)
    fun getThumbnail(url: String, width: Int, height: Int): Mono<InputStream> = Mono.defer<InputStream> {
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
