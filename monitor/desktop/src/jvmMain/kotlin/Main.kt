import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ditchoom.common.App
import com.ditchoom.mqtt.client.MqttService


fun main() = application {
    var service by remember { mutableStateOf<MqttService?>(null) }
    LaunchedEffect("MQTTService") {
        service = MqttService.buildService()
    }
    val serviceLocal = service
    if (serviceLocal != null) {
        Window(onCloseRequest = ::exitApplication) {
            App(serviceLocal)
        }
    }
}
