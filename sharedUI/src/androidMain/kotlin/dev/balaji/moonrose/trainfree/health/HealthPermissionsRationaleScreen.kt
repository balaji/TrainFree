package dev.balaji.moonrose.trainfree.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.balaji.moonrose.trainfree.PlatformTheme
import dev.balaji.moonrose.trainfree.sharedui.resources.Res
import dev.balaji.moonrose.trainfree.sharedui.resources.health_rationale_body
import dev.balaji.moonrose.trainfree.sharedui.resources.health_rationale_title
import dev.balaji.moonrose.trainfree.sharedui.resources.ok
import org.jetbrains.compose.resources.stringResource
@Composable
fun HealthPermissionsRationaleScreen(onClose: () -> Unit) {
    PlatformTheme {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.health_rationale_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(Res.string.health_rationale_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onClose) {
                    Text(stringResource(Res.string.ok))
                }
            }
        }
    }
}
