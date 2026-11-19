package dev.balaji.moonrose.trainfree.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.balaji.moonrose.trainfree.chat.AiContentReport
import dev.balaji.moonrose.trainfree.chat.AiContentReportReason
import dev.balaji.moonrose.trainfree.sharedui.resources.Res
import dev.balaji.moonrose.trainfree.sharedui.resources.ai_report_dialog_subtitle
import dev.balaji.moonrose.trainfree.sharedui.resources.ai_report_dialog_title
import dev.balaji.moonrose.trainfree.sharedui.resources.ai_report_note_placeholder
import dev.balaji.moonrose.trainfree.sharedui.resources.ai_report_submit
import dev.balaji.moonrose.trainfree.sharedui.resources.cancel
import org.jetbrains.compose.resources.stringResource

/**
 * In-app moderation dialog allowing users to flag offensive, unsafe, or
 * inaccurate on-device AI responses and save the report locally.
 */
@Composable
internal fun AiContentReportDialog(
    onSubmit: (reasonKey: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedReason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.ai_report_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.ai_report_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    AiContentReportReason.ALL.forEach { reasonKey ->
                        val selected = selectedReason == reasonKey
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { selectedReason = reasonKey },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                            )
                            Text(
                                text = aiReportReasonLabel(reasonKey),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(AiContentReport.MAX_NOTE_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    placeholder = { Text(stringResource(Res.string.ai_report_note_placeholder)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedReason != null,
                onClick = { selectedReason?.let { onSubmit(it, note) } },
            ) {
                Text(stringResource(Res.string.ai_report_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
