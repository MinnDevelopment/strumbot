package strumbot

import club.minnced.jda.reactor.toMono
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

    fun getStreamByLogin(login: String): Mono<Stream> = Mono.defer<Stream> {
        val request = Request.Builder()
            .addHeader("Client-ID", clientId)
            .url("https://api.twitch.tv/helix/streams?user_login=$login")
            .build()

        makeRequest(request) { response ->
            val json = DataObject.fromJson(response.body()!!.byteStream())
            val data = json.getArray("data")
            if (data.isEmpty) {
                null
            } else {
                val stream = data.getObject(0)
                Stream(
                    stream.getString("id"),
                    stream.getString("game_id"),
                    stream.getString("title"),
                    stream.getString("type"),
                    stream.getString("thumbnail_url"),
                    stream.getString("user_id"),
                    OffsetDateTime.parse(stream.getString("started_at"))
                )
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
            val json = DataObject.fromJson(response.body()!!.byteStream())
            val data = json.getArray("data")
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
            val json = DataObject.fromJson(response.body()!!.byteStream())
            val data = json.getArray("data")
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

    fun getLatestBroadcastByUser(userId: String): Mono<Video> = Mono.defer<Video> {
        val request = Request.Builder()
            .addHeader("Client-Id", clientId)
            .url("https://api.twitch.tv/helix/videos?user_id=$userId")
            .build()

        makeRequest(request) { response ->
            handleVideo(response)
        }
    }

    private fun handleVideo(response: Response): Video? {
        val json = DataObject.fromJson(response.body()!!.byteStream())
        val data = json.getArray("data")
        return if (data.isEmpty)
            null
        else {
            val video = data.getObject(0)
            val id = video.getString("id")
            val url = video.getString("url")
            val title = video.getString("title")
            val thumbnail = video.getString("thumbnail_url")
            Video(id, url, title, thumbnail)
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
}

data class Stream(
    val streamId: String,
    val gameId: String,
    val title: String,
    val type: String,
    val thumbnail: String,
    val userId: String,
    val startedAt: OffsetDateTime)
data class Video(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String)
data class Game(val gameId: String, val name: String)
