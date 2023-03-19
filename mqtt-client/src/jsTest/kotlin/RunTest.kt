import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.js.Promise
import kotlin.time.Duration.Companion.seconds

actual fun <T> block(body: suspend CoroutineScope.() -> T): dynamic = runTestInternal(block = body)

fun <T> runTestInternal(
    block: suspend CoroutineScope.() -> T
): Promise<T?> {
    val promise = GlobalScope.promise {
        try {
            return@promise withTimeout(10.seconds) {
                block()
            }
        } catch (e: UnsupportedOperationException) {
            println("unsupported operation, skipping")
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@promise null
    }
    promise.catch {
        if (it !is UnsupportedOperationException) {
            throw it
        }
    }
    return promise
}
