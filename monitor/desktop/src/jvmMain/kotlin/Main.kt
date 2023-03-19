import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ditchoom.common.App


fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        App()
    }
}
