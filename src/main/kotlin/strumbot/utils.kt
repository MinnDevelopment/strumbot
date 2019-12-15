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

import net.dv8tion.jda.api.JDA
import reactor.util.function.Tuple2
import reactor.util.function.Tuple3
import reactor.util.function.Tuple4

operator fun <T> Tuple2<T, *>.component1(): T = t1
operator fun <T> Tuple2<*, T>.component2(): T = t2

operator fun <T> Tuple3<T, *, *>.component1(): T = t1
operator fun <T> Tuple3<*, T, *>.component2(): T = t2
operator fun <T> Tuple3<*, *, T>.component3(): T = t3

operator fun <T> Tuple4<T, *, *, *>.component1(): T = t1
operator fun <T> Tuple4<*, T, *, *>.component2(): T = t2
operator fun <T> Tuple4<*, *, T, *>.component3(): T = t3
operator fun <T> Tuple4<*, *, *, T>.component4(): T = t4

// Convert role type to role id
private val rankByType: MutableMap<String, String> = mutableMapOf()

fun JDA.getRoleByType(configuration: Configuration, type: String): String {
    val roleName = configuration.ranks[type] ?: "0"
    if (type !in rankByType) {
        val roleId = getRolesByName(roleName, true).firstOrNull()?.id ?: return "0"
        rankByType[type] = roleId
    }
    return rankByType[type] ?: "0"
}