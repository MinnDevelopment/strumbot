///////////////////////////////////////////////////////////////////////////////////////////////////
/// This file is only here such that we can exclude java.naming from our JRE distribution.      ///
/// The exception is required for compatibility with logback-classic and is otherwise unused.   ///
///////////////////////////////////////////////////////////////////////////////////////////////////




@file:JvmName("NamingException")
package javax.naming

class NamingException constructor(explanation: String?) : Throwable(explanation) {
    constructor() : this(null)
}