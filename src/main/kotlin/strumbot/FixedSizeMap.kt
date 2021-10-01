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

private class FixedSizeMap0<K, V>(val capacity: Int) : LinkedHashMap<K, V>(capacity + 2) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
        return size > capacity
    }
}

@Suppress("FunctionName")
fun<K, V> FixedSizeMap(capacity: Int): MutableMap<K, V> = Collections.synchronizedMap(FixedSizeMap0<K, V>(capacity))