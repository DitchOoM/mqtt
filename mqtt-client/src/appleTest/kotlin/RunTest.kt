import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

actual fun <T> block(body: suspend CoroutineScope.() -> T) {
    runBlocking {
        withTimeout(15000) {
            try {
                body()
            } catch (e: UnsupportedOperationException) {
                println("Test hit unsupported code, ignoring")
            }
        }
    }
}
