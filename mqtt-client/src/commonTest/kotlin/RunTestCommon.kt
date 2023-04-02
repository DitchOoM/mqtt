import kotlinx.coroutines.CoroutineScope

expect fun <T> block(body: suspend CoroutineScope.() -> T)

fun blockWithResult(body: suspend CoroutineScope.() -> Boolean): Boolean {
    var result: Boolean? = null
    block {
        result = body()
    }
    return result ?: false
}
