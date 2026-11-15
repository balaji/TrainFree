package dev.balaji.moonrose.trainfree.navigation

import androidx.compose.runtime.Composable
import dev.balaji.moonrose.trainfree.chat.ChatSeedContext
import dev.balaji.moonrose.trainfree.screens.CalendarScreen
import dev.balaji.moonrose.trainfree.screens.DayDetailScreen
import dev.balaji.moonrose.trainfree.screens.ExerciseCreateScreen
import dev.balaji.moonrose.trainfree.screens.ExerciseDetailScreen
import dev.balaji.moonrose.trainfree.screens.ExercisesScreen
import dev.balaji.moonrose.trainfree.screens.JournalEditorScreen
import dev.balaji.moonrose.trainfree.screens.ProfileScreen
import dev.balaji.moonrose.trainfree.screens.ProgramsScreen
import dev.balaji.moonrose.trainfree.screens.ProgressScreen
import dev.balaji.moonrose.trainfree.screens.RoutineDetailScreen
import dev.balaji.moonrose.trainfree.screens.WorkoutEditScreen
import dev.balaji.moonrose.trainfree.screens.WorkoutLogScreen
import dev.balaji.moonrose.trainfree.screens.WorkoutSessionScreen

/**
 * Single route-to-screen dispatcher used by every platform entry point:
 * the shared App() scaffold on Android and the per-screen
 * ComposeUIViewControllers on iOS.
 */
@Composable
fun ScreenContent(
    route: Route,
    onNavigate: (Route) -> Unit,
    onGoBack: () -> Unit,
    onOpenChat: ((ChatSeedContext) -> Unit)? = null,
) {
    when (route) {
        ExercisesRoute -> ExercisesScreen(onNavigate = onNavigate)
        WorkoutsRoute -> ProgramsScreen(onNavigate = onNavigate, onGoBack = onGoBack)
        CalendarRoute -> CalendarScreen(
            onDayClick = { epochDay -> onNavigate(DayDetailRoute(epochDay)) },
            onLogWorkout = { epochDay -> onNavigate(WorkoutLogRoute(epochDay)) },
            onEditJournal = { epochDay -> onNavigate(JournalEditorRoute(epochDay)) },
        )
        ProgressRoute -> ProgressScreen(
            onOpenChat = onOpenChat?.let { open -> { open(ChatSeedContext()) } },
        )
        ProfileRoute -> ProfileScreen()
        is RoutineDetailRoute -> RoutineDetailScreen(route = route, onNavigate = onNavigate, onGoBack = onGoBack)
        ExerciseCreateRoute -> ExerciseCreateScreen(onGoBack = onGoBack)
        is ExerciseDetailRoute -> ExerciseDetailScreen(
            route = route,
            onGoBack = onGoBack,
            onOpenChat = onOpenChat,
        )
        is WorkoutSessionRoute -> WorkoutSessionScreen(route = route, onGoBack = onGoBack)
        is WorkoutLogRoute -> WorkoutLogScreen(route = route, onGoBack = onGoBack)
        is WorkoutEditRoute -> WorkoutEditScreen(route = route, onGoBack = onGoBack)
        is JournalEditorRoute -> JournalEditorScreen(route = route, onGoBack = onGoBack)
        is DayDetailRoute -> DayDetailScreen(
            route = route,
            onGoBack = onGoBack,
            onOpenChat = onOpenChat,
            onLogWorkout = { onNavigate(WorkoutLogRoute(route.epochDay)) },
            onEditSession = { sessionId -> onNavigate(WorkoutEditRoute(sessionId)) },
            onEditJournal = { onNavigate(JournalEditorRoute(route.epochDay)) },
        )
    }
}
