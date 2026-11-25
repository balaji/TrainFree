package dev.balaji.moonrose.trainfree.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.balaji.moonrose.trainfree.model.NewOneRepMaxAchievement
import dev.balaji.moonrose.trainfree.model.WeightUnit
import dev.balaji.moonrose.trainfree.model.WorkoutCompletionCelebration
import dev.balaji.moonrose.trainfree.sharedui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Celebratory modal affirmation pop-up shown after finishing a live workout session.
 */
@Composable
internal fun WorkoutCompletionDialog(
    celebration: WorkoutCompletionCelebration,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val emojiScale = remember(celebration.sessionId) { Animatable(0.55f) }

    LaunchedEffect(celebration.sessionId) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        emojiScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = celebration.emoji,
                        fontSize = 48.sp,
                        modifier = Modifier.graphicsLayer {
                            scaleX = emojiScale.value
                            scaleY = emojiScale.value
                        },
                    )
                }

                Text(
                    text = stringResource(Res.string.workout_complete_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                if (celebration.workoutTitle.isNotBlank()) {
                    Text(
                        text = celebration.workoutTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (celebration.newOneRepMaxes.isNotEmpty()) {
                    OneRepMaxHighlightCard(
                        achievements = celebration.newOneRepMaxes,
                        weightUnit = celebration.weightUnit,
                    )
                } else if (celebration.exerciseCount > 0 && celebration.completedSets > 0) {
                    val summaryParts = listOfNotNull(
                        pluralStringResource(
                            Res.plurals.exercise_count,
                            celebration.exerciseCount,
                            celebration.exerciseCount,
                        ),
                        pluralStringResource(
                            Res.plurals.exercise_set_count,
                            celebration.completedSets,
                            celebration.completedSets,
                        ),
                        celebration.durationSeconds.takeIf { it > 0L }?.let(::formatElapsed),
                    ).joinToString(" · ")

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = summaryParts,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(stringResource(Res.string.ok))
                }
            }
        }
    }
}

@Composable
private fun OneRepMaxHighlightCard(
    achievements: List<NewOneRepMaxAchievement>,
    weightUnit: WeightUnit,
) {
    val unitLabel = weightUnitLabel(weightUnit)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = pluralStringResource(
                    Res.plurals.workout_complete_new_1rm_header,
                    achievements.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                achievements.forEachIndexed { index, achievement ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = achievement.exerciseName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = when (weightUnit) {
                                    WeightUnit.METRIC -> stringResource(
                                        Res.string.weight_kg_value,
                                        achievement.formattedNewOneRm,
                                    )
                                    WeightUnit.IMPERIAL -> stringResource(
                                        Res.string.weight_lb_value,
                                        achievement.formattedNewOneRm,
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(
                                    Res.string.workout_complete_1rm_gain,
                                    achievement.formattedGain,
                                    unitLabel,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}
