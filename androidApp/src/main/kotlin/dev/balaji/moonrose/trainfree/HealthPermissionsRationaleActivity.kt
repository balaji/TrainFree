package dev.balaji.moonrose.trainfree

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.balaji.moonrose.trainfree.health.HealthPermissionsRationaleScreen

/**
 * Required by Google Health Connect: explains why TrainFree requests Exercise
 * write and Weight read/write permissions when tapped from the OS Health
 * Connect permission screen.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HealthPermissionsRationaleScreen(onClose = { finish() })
        }
    }
}
