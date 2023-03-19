import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual fun <T> block(body: suspend CoroutineScope.() -> T) {
    runBlocking(block = body)
}
