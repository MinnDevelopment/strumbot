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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resumeWithException

inline fun <T : AutoCloseable, R> T.useCatching(fn: () -> R) = runCatching {
    fn()
}.also { close() }

suspend inline fun <T> Call.await(scope: CoroutineScope, crossinline callback: suspend (Response) -> T) = suspendCancellableCoroutine<T> { sink ->
    sink.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            sink.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            scope.launch {
                response.useCatching {
                    callback(response)
                }.also(sink::resumeWith)
            }
        }
    })
}

class HttpException(
    route: String, status: Int, meaning: String
): Exception("$route > $status: $meaning")

class NotAuthorized(
    response: Response
): Exception("Authorization failed. Code: ${response.code} Body: ${response.body?.string()}")

fun Response.asException() = HttpException(request.url.toString(), code, message)

inline fun post(url: String, form: FormBody.Builder.() -> Unit): Request {
    val body = FormBody.Builder()
    body.form()
    return Request.Builder()
        .url(url)
        .method("POST", body.build())
        .build()
}