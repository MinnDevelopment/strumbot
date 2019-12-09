package strumbot

import java.util.*

private class FixedSizeMap0<K, V>(val capacity: Int) : LinkedHashMap<K, V>(capacity + 2) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
        return size > capacity
    }
}

@Suppress("FunctionName")
fun<K, V> FixedSizeMap(capacity: Int): MutableMap<K, V> = Collections.synchronizedMap(FixedSizeMap0<K, V>(capacity))