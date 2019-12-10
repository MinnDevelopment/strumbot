package strumbot

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