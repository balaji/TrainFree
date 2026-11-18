package dev.balaji.moonrose.trainfree.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.balaji.moonrose.trainfree.chat.AiExerciseInput
import dev.balaji.moonrose.trainfree.chat.AiReplaceExerciseInput
import dev.balaji.moonrose.trainfree.chat.AiRoutineInput
import dev.balaji.moonrose.trainfree.chat.ExerciseMatchResult
import dev.balaji.moonrose.trainfree.chat.ExerciseMatcher
import dev.balaji.moonrose.trainfree.model.Exercise
import dev.balaji.moonrose.trainfree.model.ExerciseCatalog
import dev.balaji.moonrose.trainfree.model.ExerciseType
import dev.balaji.moonrose.trainfree.model.Program
import dev.balaji.moonrose.trainfree.model.Routine
import dev.balaji.moonrose.trainfree.model.SetType
import dev.balaji.moonrose.trainfree.model.WeightUnit

internal sealed interface AiCreateExerciseResult {
    data class Created(val exercise: Exercise) : AiCreateExerciseResult
    data class AlreadyExists(val exercise: Exercise) : AiCreateExerciseResult
    data class Invalid(val message: String) : AiCreateExerciseResult
}

internal data class CreatedRoutineSummary(
    val routine: Routine,
    val exercises: List<Pair<Exercise, List<WorkSetDetailEntity>>>,
)

internal sealed interface AiCreateProgramResult {
    data class Created(
        val program: Program,
        val routines: List<CreatedRoutineSummary>,
        val createdExercises: List<Exercise>,
    ) : AiCreateProgramResult

    data class Invalid(val message: String) : AiCreateProgramResult
}

internal sealed interface AiRoutineAddResult {
    data class Added(
        val program: Program,
        val programCreated: Boolean,
        val routine: Routine,
        val exercises: List<Pair<Exercise, List<WorkSetDetailEntity>>>,
        val createdExercises: List<Exercise>,
    ) : AiRoutineAddResult

    data class AmbiguousProgram(val candidates: List<String>) : AiRoutineAddResult
    data class ProgramRequired(val candidates: List<String>) : AiRoutineAddResult
    data class Invalid(val message: String) : AiRoutineAddResult
}

internal sealed interface AiRoutineUpdateResult {
    data class Updated(
        val program: Program,
        val routine: Routine,
        val renamedFromTo: Pair<String, String>?,
        val replaced: List<Pair<String, String>>,
        val updated: List<String>,
        val removed: List<String>,
        val added: List<String>,
        val notFoundInRoutine: List<String>,
        val createdExercises: List<Exercise>,
        val finalExercises: List<Pair<Exercise, List<WorkSetDetailEntity>>>,
    ) : AiRoutineUpdateResult

    data class NotFound(val query: String) : AiRoutineUpdateResult
    data class Ambiguous(val query: String, val candidates: List<String>) : AiRoutineUpdateResult
    data class Invalid(val message: String) : AiRoutineUpdateResult
}

internal interface ProgramAiStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T
    suspend fun getAllExercises(): List<Exercise>
    suspend fun insertExercise(entity: ExerciseEntity): Long
    suspend fun getCompletedSetCountsByExercise(): Map<Long, Int>
    suspend fun getAllPrograms(): List<Program>
    suspend fun insertProgram(name: String, now: Long): Long
    suspend fun getAllRoutines(): List<Routine>
    suspend fun nextRoutinePosition(programId: Long): Int
    suspend fun insertRoutine(programId: Long, name: String, position: Int, now: Long): Long
    suspend fun renameRoutine(routineId: Long, name: String, now: Long)
    suspend fun getWorkSetsForRoutine(routineId: Long): List<WorkSetEntity>
    suspend fun getWorkSetDetailsForWorkSets(workSetIds: List<Long>): List<WorkSetDetailEntity>
    suspend fun deleteWorkSetsForRoutine(routineId: Long)
    suspend fun insertWorkSet(routineId: Long, exerciseId: Long, position: Int, now: Long): Long
    suspend fun insertWorkSetDetail(detail: WorkSetDetailEntity): Long
}

internal class RoomProgramAiStore(private val db: AppDatabase) : ProgramAiStore {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }

    override suspend fun getAllExercises(): List<Exercise> =
        db.exerciseDao().getAll().map { it.toDomainModel() }

    override suspend fun insertExercise(entity: ExerciseEntity): Long =
        db.exerciseDao().insert(entity)

    override suspend fun getCompletedSetCountsByExercise(): Map<Long, Int> =
        db.workoutSessionDetailDao()
            .getCompletedSetCountsByExercise(PROFILE_ID)
            .associate { it.exerciseId to it.setCount }

    override suspend fun getAllPrograms(): List<Program> =
        db.programDao().getAll().map { Program(it.id, it.name) }

    override suspend fun insertProgram(name: String, now: Long): Long =
        db.programDao().insert(
            ProgramEntity(profileId = PROFILE_ID, name = name, createdAt = now, updatedAt = now),
        )

    override suspend fun getAllRoutines(): List<Routine> =
        db.routineDao().getAll().map { Routine(it.id, it.name, it.programId, it.position) }

    override suspend fun nextRoutinePosition(programId: Long): Int =
        db.routineDao().nextPosition(programId)

    override suspend fun insertRoutine(programId: Long, name: String, position: Int, now: Long): Long =
        db.routineDao().insert(
            RoutineEntity(
                programId = programId,
                name = name,
                position = position,
                createdAt = now,
                updatedAt = now,
            ),
        )

    override suspend fun renameRoutine(routineId: Long, name: String, now: Long) =
        db.routineDao().rename(routineId, name, now)

    override suspend fun getWorkSetsForRoutine(routineId: Long): List<WorkSetEntity> =
        db.workSetDao().getForRoutine(routineId)

    override suspend fun getWorkSetDetailsForWorkSets(workSetIds: List<Long>): List<WorkSetDetailEntity> =
        if (workSetIds.isEmpty()) emptyList() else db.workSetDetailDao().getForWorkSets(workSetIds)

    override suspend fun deleteWorkSetsForRoutine(routineId: Long) =
        db.workSetDao().deleteForRoutine(routineId)

    override suspend fun insertWorkSet(routineId: Long, exerciseId: Long, position: Int, now: Long): Long =
        db.workSetDao().insert(
            WorkSetEntity(
                routineId = routineId,
                exerciseId = exerciseId,
                position = position,
                createdAt = now,
                updatedAt = now,
            ),
        )

    override suspend fun insertWorkSetDetail(detail: WorkSetDetailEntity): Long =
        db.workSetDetailDao().insert(detail)
}

internal suspend fun AppDatabase.createCustomExercise(
    name: String,
    bodyPart: String,
    equipment: String? = null,
    exerciseType: String? = null,
    otherBodyParts: List<String> = emptyList(),
    now: Long = nowMillis(),
): AiCreateExerciseResult =
    RoomProgramAiStore(this).createCustomExercise(name, bodyPart, equipment, exerciseType, otherBodyParts, now)

internal suspend fun AppDatabase.createAiProgram(
    programName: String,
    routines: List<AiRoutineInput>,
    unit: WeightUnit,
    now: Long = nowMillis(),
): AiCreateProgramResult =
    RoomProgramAiStore(this).createAiProgram(programName, routines, unit, now)

internal suspend fun AppDatabase.addAiRoutineToProgram(
    routineName: String,
    exercises: List<AiExerciseInput>,
    programName: String? = null,
    unit: WeightUnit,
    now: Long = nowMillis(),
): AiRoutineAddResult =
    RoomProgramAiStore(this).addAiRoutineToProgram(routineName, exercises, programName, unit, now)

internal suspend fun AppDatabase.updateAiRoutine(
    routineName: String,
    programName: String? = null,
    newRoutineName: String? = null,
    addExercises: List<AiExerciseInput> = emptyList(),
    replaceExercises: List<AiReplaceExerciseInput> = emptyList(),
    updateExercises: List<AiExerciseInput> = emptyList(),
    removeExercises: List<String> = emptyList(),
    unit: WeightUnit,
    now: Long = nowMillis(),
): AiRoutineUpdateResult =
    RoomProgramAiStore(this).updateAiRoutine(
        routineName = routineName,
        programName = programName,
        newRoutineName = newRoutineName,
        addExercises = addExercises,
        replaceExercises = replaceExercises,
        updateExercises = updateExercises,
        removeExercises = removeExercises,
        unit = unit,
        now = now,
    )

internal suspend fun ProgramAiStore.createCustomExercise(
    name: String,
    bodyPart: String,
    equipment: String? = null,
    exerciseType: String? = null,
    otherBodyParts: List<String> = emptyList(),
    now: Long = nowMillis(),
): AiCreateExerciseResult {
    val cleanName = cleanExerciseName(name)
        ?: return AiCreateExerciseResult.Invalid("Specify a valid exercise name.")
    val normBodyPart = ExerciseCatalog.normalizeBodyPart(bodyPart)
    val normOther = ExerciseCatalog.normalizeOtherBodyParts(otherBodyParts, normBodyPart)
    val normEquipment = ExerciseCatalog.normalizeEquipment(equipment)
    val normType = ExerciseCatalog.normalizeExerciseType(exerciseType)

    val existing = getAllExercises().firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
    if (existing != null) {
        return AiCreateExerciseResult.AlreadyExists(existing)
    }

    val entity = ExerciseEntity(
        name = cleanName,
        exerciseType = normType,
        bodyPart = normBodyPart,
        otherBodyParts = normOther.takeIf { it.isNotEmpty() },
        equipment = normEquipment,
        builtin = false,
        createdAt = now,
        updatedAt = now,
    )
    val id = insertExercise(entity)
    return AiCreateExerciseResult.Created(entity.copy(id = id).toDomainModel())
}

internal suspend fun ProgramAiStore.createAiProgram(
    programName: String,
    routines: List<AiRoutineInput>,
    unit: WeightUnit,
    now: Long = nowMillis(),
): AiCreateProgramResult {
    val cleanProgram = cleanNamedEntity(programName)
        ?: return AiCreateProgramResult.Invalid("Specify a non-empty program name.")
    if (routines.isEmpty()) {
        return AiCreateProgramResult.Invalid("Include at least one routine with exercises to create a program.")
    }
    val nonEmptyRoutines = routines.filter { it.exercises.isNotEmpty() }
    if (nonEmptyRoutines.isEmpty()) {
        return AiCreateProgramResult.Invalid("Each routine must include at least one exercise.")
    }

    return inTransaction {
        val catalog = getAllExercises().toMutableList()
        val trainedCounts = getCompletedSetCountsByExercise()
        val createdExercises = mutableListOf<Exercise>()

        val programId = insertProgram(cleanProgram, now)
        val createdRoutines = mutableListOf<CreatedRoutineSummary>()

        nonEmptyRoutines.forEachIndexed { rIdx, rInput ->
            val cleanRoutine = cleanNamedEntity(rInput.name) ?: "Day ${rIdx + 1}"
            val routineId = insertRoutine(programId, cleanRoutine, rIdx + 1, now)
            val insertedExercises = insertRoutineExercisesInTx(
                routineId = routineId,
                inputs = rInput.exercises,
                catalog = catalog,
                trainedCounts = trainedCounts,
                createdExercises = createdExercises,
                unit = unit,
                now = now,
            )
            createdRoutines += CreatedRoutineSummary(
                routine = Routine(id = routineId, name = cleanRoutine, programId = programId, position = rIdx + 1),
                exercises = insertedExercises,
            )
        }

        AiCreateProgramResult.Created(
            program = Program(id = programId, name = cleanProgram),
            routines = createdRoutines,
            createdExercises = createdExercises,
        )
    }
}

internal suspend fun ProgramAiStore.addAiRoutineToProgram(
    routineName: String,
    exercises: List<AiExerciseInput>,
    programName: String? = null,
    unit: WeightUnit,
    now: Long = nowMillis(),
): AiRoutineAddResult {
    val cleanRoutine = cleanNamedEntity(routineName)
        ?: return AiRoutineAddResult.Invalid("Specify a non-empty routine name.")
    if (exercises.isEmpty()) {
        return AiRoutineAddResult.Invalid("Include at least one exercise for the routine.")
    }

    val existingPrograms = getAllPrograms()
    val cleanProgName = programName?.let(::cleanNamedEntity)

    val matchedProgram: Program?
    var createNewProgramName: String? = null
    if (cleanProgName != null) {
        val matches = matchPrograms(existingPrograms, cleanProgName)
        when {
            matches.size == 1 -> matchedProgram = matches.single()
            matches.size > 1 -> return AiRoutineAddResult.AmbiguousProgram(matches.map { it.name })
            else -> {
                matchedProgram = null
                createNewProgramName = cleanProgName
            }
        }
    } else {
        when (existingPrograms.size) {
            0 -> {
                matchedProgram = null
                createNewProgramName = "My Program"
            }
            1 -> matchedProgram = existingPrograms.single()
            else -> return AiRoutineAddResult.ProgramRequired(existingPrograms.map { it.name })
        }
    }

    return inTransaction {
        val catalog = getAllExercises().toMutableList()
        val trainedCounts = getCompletedSetCountsByExercise()
        val createdExercises = mutableListOf<Exercise>()

        val targetProgram = matchedProgram ?: run {
            val pName = checkNotNull(createNewProgramName)
            val pid = insertProgram(pName, now)
            Program(id = pid, name = pName)
        }

        val position = nextRoutinePosition(targetProgram.id)
        val routineId = insertRoutine(targetProgram.id, cleanRoutine, position, now)
        val insertedExercises = insertRoutineExercisesInTx(
            routineId = routineId,
            inputs = exercises,
            catalog = catalog,
            trainedCounts = trainedCounts,
            createdExercises = createdExercises,
            unit = unit,
            now = now,
        )

        AiRoutineAddResult.Added(
            program = targetProgram,
            programCreated = matchedProgram == null,
            routine = Routine(id = routineId, name = cleanRoutine, programId = targetProgram.id, position = position),
            exercises = insertedExercises,
            createdExercises = createdExercises,
        )
    }
}

internal suspend fun ProgramAiStore.updateAiRoutine(
    routineName: String,
    programName: String? = null,
    newRoutineName: String? = null,
    addExercises: List<AiExerciseInput> = emptyList(),
    replaceExercises: List<AiReplaceExerciseInput> = emptyList(),
    updateExercises: List<AiExerciseInput> = emptyList(),
    removeExercises: List<String> = emptyList(),
    unit: WeightUnit,
    now: Long = nowMillis(),
): AiRoutineUpdateResult {
    val cleanRoutineQuery = cleanNamedEntity(routineName)
        ?: return AiRoutineUpdateResult.Invalid("Specify the name of the routine to update.")

    val cleanNewName = newRoutineName?.let(::cleanNamedEntity)
    if (cleanNewName == null &&
        addExercises.isEmpty() &&
        replaceExercises.isEmpty() &&
        updateExercises.isEmpty() &&
        removeExercises.isEmpty()
    ) {
        return AiRoutineUpdateResult.Invalid("Specify at least one change to apply to \"$cleanRoutineQuery\".")
    }

    val allPrograms = getAllPrograms()
    val allRoutines = getAllRoutines()
    if (allRoutines.isEmpty()) {
        return AiRoutineUpdateResult.NotFound(cleanRoutineQuery)
    }

    val programFilter = programName?.let(::cleanNamedEntity)
    val candidatePrograms = if (programFilter != null) {
        matchPrograms(allPrograms, programFilter)
    } else {
        allPrograms
    }
    if (programFilter != null && candidatePrograms.isEmpty()) {
        return AiRoutineUpdateResult.NotFound("$programFilter / $cleanRoutineQuery")
    }
    val candidateProgramIds = candidatePrograms.map { it.id }.toSet()
    val scopedRoutines = allRoutines.filter { it.programId in candidateProgramIds }
    val matchedRoutines = matchRoutines(scopedRoutines, cleanRoutineQuery)

    if (matchedRoutines.isEmpty()) {
        return AiRoutineUpdateResult.NotFound(cleanRoutineQuery)
    }
    val programsById = allPrograms.associateBy { it.id }
    if (matchedRoutines.size > 1) {
        val labels = matchedRoutines.map { r ->
            val pName = programsById[r.programId]?.name ?: "Program"
            "$pName — ${r.name}"
        }
        return AiRoutineUpdateResult.Ambiguous(cleanRoutineQuery, labels)
    }

    val targetRoutine = matchedRoutines.single()
    val targetProgram = programsById[targetRoutine.programId] ?: Program(targetRoutine.programId, "Program")

    return inTransaction {
        val catalog = getAllExercises().toMutableList()
        val catalogById = catalog.associateBy { it.id }
        val trainedCounts = getCompletedSetCountsByExercise()
        val createdExercises = mutableListOf<Exercise>()

        val existingWorkSets = getWorkSetsForRoutine(targetRoutine.id)
        val detailMap = getWorkSetDetailsForWorkSets(existingWorkSets.map { it.id }).groupBy { it.workSetId }

        val items = existingWorkSets.mapNotNull { ws ->
            val ex = catalogById[ws.exerciseId] ?: return@mapNotNull null
            val details = detailMap[ws.id].orEmpty().map { d ->
                DetailSpec(
                    targetWeight = d.targetWeight,
                    targetReps = d.targetReps,
                    targetDuration = d.targetDuration,
                    targetDistance = d.targetDistance,
                    setType = d.setType,
                    weightUnit = d.weightUnit,
                )
            }
            MutableRoutineItem(ex, details.ifEmpty { defaultDetailsFor(ex, AiExerciseInput(ex.name), unit) })
        }.toMutableList()

        val replaced = mutableListOf<Pair<String, String>>()
        val updated = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        val notFoundInRoutine = mutableListOf<String>()

        for (rep in replaceExercises) {
            val idx = findItemIndex(items, rep.targetExerciseName)
            if (idx < 0) {
                notFoundInRoutine += rep.targetExerciseName
                continue
            }
            val oldItem = items[idx]
            val newExInput = AiExerciseInput(
                exerciseName = rep.newExerciseName,
                sets = rep.sets ?: oldItem.details.count { it.setType != SetType.WARMUP }.coerceAtLeast(1),
                reps = rep.reps,
                weight = rep.weight,
                durationSeconds = rep.durationSeconds,
                distance = rep.distance,
                bodyPart = rep.bodyPart,
                otherBodyParts = rep.otherBodyParts,
                equipment = rep.equipment,
                exerciseType = rep.exerciseType,
            )
            val newExercise = resolveOrCreateExerciseInTx(
                input = newExInput,
                catalog = catalog,
                trainedCounts = trainedCounts,
                createdExercises = createdExercises,
                now = now,
            )
            val hasExplicitTargets = rep.sets != null ||
                rep.reps != null ||
                rep.weight != null ||
                rep.durationSeconds != null ||
                rep.distance != null
            val newDetails = if (hasExplicitTargets) {
                defaultDetailsFor(newExercise, newExInput, unit)
            } else {
                adaptDetailsForReplacement(oldItem.details, newExercise, unit)
            }
            items[idx] = MutableRoutineItem(newExercise, newDetails)
            replaced += oldItem.exercise.name to newExercise.name
        }

        for (upd in updateExercises) {
            val idx = findItemIndex(items, upd.exerciseName)
            if (idx < 0) {
                notFoundInRoutine += upd.exerciseName
                continue
            }
            val current = items[idx]
            items[idx] = MutableRoutineItem(
                exercise = current.exercise,
                details = applyDetailUpdates(current.details, current.exercise, upd, unit),
            )
            updated += current.exercise.name
        }

        for (remQuery in removeExercises) {
            val idx = findItemIndex(items, remQuery)
            if (idx < 0) {
                notFoundInRoutine += remQuery
                continue
            }
            val removedItem = items.removeAt(idx)
            removed += removedItem.exercise.name
        }

        for (addInput in addExercises) {
            val ex = resolveOrCreateExerciseInTx(
                input = addInput,
                catalog = catalog,
                trainedCounts = trainedCounts,
                createdExercises = createdExercises,
                now = now,
            )
            val details = defaultDetailsFor(ex, addInput, unit)
            items += MutableRoutineItem(ex, details)
            added += ex.name
        }

        val renamedFromTo = if (cleanNewName != null && cleanNewName != targetRoutine.name) {
            renameRoutine(targetRoutine.id, cleanNewName, now)
            targetRoutine.name to cleanNewName
        } else {
            null
        }

        deleteWorkSetsForRoutine(targetRoutine.id)
        val persistedSummary = mutableListOf<Pair<Exercise, List<WorkSetDetailEntity>>>()
        items.forEachIndexed { wsIdx, item ->
            val wsId = insertWorkSet(targetRoutine.id, item.exercise.id, wsIdx + 1, now)
            val detailEntities = item.details.mapIndexed { sIdx, spec ->
                val entity = WorkSetDetailEntity(
                    workSetId = wsId,
                    position = sIdx + 1,
                    targetWeight = spec.targetWeight,
                    targetReps = spec.targetReps,
                    targetDuration = spec.targetDuration,
                    targetDistance = spec.targetDistance,
                    setType = spec.setType,
                    weightUnit = spec.weightUnit,
                    createdAt = now,
                    updatedAt = now,
                )
                val id = insertWorkSetDetail(entity)
                entity.copy(id = id)
            }
            persistedSummary += item.exercise to detailEntities
        }

        val updatedRoutine = targetRoutine.copy(name = renamedFromTo?.second ?: targetRoutine.name)
        AiRoutineUpdateResult.Updated(
            program = targetProgram,
            routine = updatedRoutine,
            renamedFromTo = renamedFromTo,
            replaced = replaced,
            updated = updated,
            removed = removed,
            added = added,
            notFoundInRoutine = notFoundInRoutine,
            createdExercises = createdExercises,
            finalExercises = persistedSummary,
        )
    }
}

private data class DetailSpec(
    val targetWeight: Double?,
    val targetReps: Int?,
    val targetDuration: Int?,
    val targetDistance: Double?,
    val setType: SetType,
    val weightUnit: WeightUnit,
)

private data class MutableRoutineItem(
    val exercise: Exercise,
    val details: List<DetailSpec>,
)

private suspend fun ProgramAiStore.insertRoutineExercisesInTx(
    routineId: Long,
    inputs: List<AiExerciseInput>,
    catalog: MutableList<Exercise>,
    trainedCounts: Map<Long, Int>,
    createdExercises: MutableList<Exercise>,
    unit: WeightUnit,
    now: Long,
): List<Pair<Exercise, List<WorkSetDetailEntity>>> {
    val result = mutableListOf<Pair<Exercise, List<WorkSetDetailEntity>>>()
    inputs.forEachIndexed { wsIdx, input ->
        if (input.exerciseName.isBlank()) return@forEachIndexed
        val exercise = resolveOrCreateExerciseInTx(
            input = input,
            catalog = catalog,
            trainedCounts = trainedCounts,
            createdExercises = createdExercises,
            now = now,
        )
        val wsId = insertWorkSet(routineId, exercise.id, wsIdx + 1, now)
        val specs = defaultDetailsFor(exercise, input, unit)
        val entities = specs.mapIndexed { sIdx, spec ->
            val entity = WorkSetDetailEntity(
                workSetId = wsId,
                position = sIdx + 1,
                targetWeight = spec.targetWeight,
                targetReps = spec.targetReps,
                targetDuration = spec.targetDuration,
                targetDistance = spec.targetDistance,
                setType = spec.setType,
                weightUnit = spec.weightUnit,
                createdAt = now,
                updatedAt = now,
            )
            val id = insertWorkSetDetail(entity)
            entity.copy(id = id)
        }
        result += exercise to entities
    }
    return result
}

private suspend fun ProgramAiStore.resolveOrCreateExerciseInTx(
    input: AiExerciseInput,
    catalog: MutableList<Exercise>,
    trainedCounts: Map<Long, Int>,
    createdExercises: MutableList<Exercise>,
    now: Long,
): Exercise {
    val cleanName = cleanExerciseName(input.exerciseName) ?: "Custom Exercise"

    catalog.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }?.let { return it }

    val requestedEquip = ExerciseCatalog.normalizeEquipment(input.equipment)
    val requestedBodyPart = input.bodyPart?.takeIf { it.isNotBlank() }?.let(ExerciseCatalog::normalizeBodyPart)

    when (val match = ExerciseMatcher.resolve(cleanName, catalog, trainedCounts)) {
        is ExerciseMatchResult.Matched -> {
            val ex = match.exercise
            val equipConflict = requestedEquip != null &&
                ex.equipment != null &&
                !ex.equipment.equals(requestedEquip, ignoreCase = true)
            if (!equipConflict) return ex
        }
        is ExerciseMatchResult.Ambiguous -> {
            val filtered = match.candidates.filter { candidate ->
                val equipOk = requestedEquip == null ||
                    candidate.equipment == null ||
                    candidate.equipment.equals(requestedEquip, ignoreCase = true)
                val partOk = requestedBodyPart == null ||
                    candidate.bodyPart == null ||
                    candidate.bodyPart.equals(requestedBodyPart, ignoreCase = true)
                equipOk && partOk
            }
            if (filtered.isNotEmpty()) return filtered.first()
            if (requestedEquip == null) return match.candidates.first()
        }
        is ExerciseMatchResult.NotFound -> Unit
    }

    val normBodyPart = requestedBodyPart ?: inferBodyPart(cleanName, input)
    val normOther = ExerciseCatalog.normalizeOtherBodyParts(input.otherBodyParts, normBodyPart)
    val normType = ExerciseCatalog.normalizeExerciseType(input.exerciseType ?: inferExerciseType(input))
    val entity = ExerciseEntity(
        name = cleanName,
        exerciseType = normType,
        bodyPart = normBodyPart,
        otherBodyParts = normOther.takeIf { it.isNotEmpty() },
        equipment = requestedEquip ?: inferEquipment(cleanName),
        builtin = false,
        createdAt = now,
        updatedAt = now,
    )
    val id = insertExercise(entity)
    val created = entity.copy(id = id).toDomainModel()
    catalog += created
    createdExercises += created
    return created
}

private fun defaultDetailsFor(
    exercise: Exercise,
    input: AiExerciseInput,
    unit: WeightUnit,
): List<DetailSpec> {
    val type = ExerciseType.fromValue(exercise.exerciseType) ?: ExerciseType.STRENGTH
    val workingCount = input.sets.coerceIn(1, 15)
    val warmupCount = input.warmupSets.coerceIn(0, 5)
    val targetWeight = input.weight?.takeIf { it > 0.0 }
    val targetReps = when (type) {
        ExerciseType.STRENGTH,
        ExerciseType.REPS_DURATION,
        ExerciseType.REPS_DISTANCE,
        -> (input.reps?.takeIf { it > 0 }) ?: 10
        ExerciseType.DURATION,
        ExerciseType.DISTANCE,
        ExerciseType.DISTANCE_DURATION,
        -> null
    }
    val targetDuration = when (type) {
        ExerciseType.DURATION,
        ExerciseType.REPS_DURATION,
        ExerciseType.DISTANCE_DURATION,
        -> (input.durationSeconds?.takeIf { it > 0 }) ?: if (type == ExerciseType.DURATION) 60 else null
        else -> null
    }
    val targetDistance = when (type) {
        ExerciseType.DISTANCE,
        ExerciseType.REPS_DISTANCE,
        ExerciseType.DISTANCE_DURATION,
        -> input.distance?.takeIf { it > 0.0 }
        else -> null
    }

    val list = mutableListOf<DetailSpec>()
    repeat(warmupCount) {
        list += DetailSpec(
            targetWeight = targetWeight?.let { (it * 0.5).takeIf { w -> w > 0.0 } },
            targetReps = targetReps,
            targetDuration = targetDuration,
            targetDistance = targetDistance,
            setType = SetType.WARMUP,
            weightUnit = unit,
        )
    }
    repeat(workingCount) {
        list += DetailSpec(
            targetWeight = targetWeight,
            targetReps = targetReps,
            targetDuration = targetDuration,
            targetDistance = targetDistance,
            setType = SetType.NORMAL,
            weightUnit = unit,
        )
    }
    return list
}

private fun adaptDetailsForReplacement(
    previous: List<DetailSpec>,
    newExercise: Exercise,
    unit: WeightUnit,
): List<DetailSpec> {
    val newType = ExerciseType.fromValue(newExercise.exerciseType) ?: ExerciseType.STRENGTH
    val working = previous.filter { it.setType != SetType.WARMUP }
    val warmups = previous.filter { it.setType == SetType.WARMUP }
    if (working.isEmpty()) return defaultDetailsFor(newExercise, AiExerciseInput(newExercise.name), unit)

    val sample = working.first()
    val seedInput = AiExerciseInput(
        exerciseName = newExercise.name,
        sets = working.size,
        reps = sample.targetReps,
        weight = null,
        durationSeconds = sample.targetDuration,
        distance = sample.targetDistance,
        warmupSets = warmups.size,
        exerciseType = newType.value,
    )
    return defaultDetailsFor(newExercise, seedInput, sample.weightUnit)
}

private fun applyDetailUpdates(
    existing: List<DetailSpec>,
    exercise: Exercise,
    update: AiExerciseInput,
    unit: WeightUnit,
): List<DetailSpec> {
    val warmups = existing.filter { it.setType == SetType.WARMUP }
    val working = existing.filter { it.setType != SetType.WARMUP }
    val base = working.firstOrNull() ?: DetailSpec(null, 10, null, null, SetType.NORMAL, unit)
    val type = ExerciseType.fromValue(exercise.exerciseType) ?: ExerciseType.STRENGTH

    val targetCount = update.sets.takeIf { it > 0 }?.coerceIn(1, 15) ?: working.size.coerceAtLeast(1)
    val nextWeight = update.weight?.takeIf { it > 0.0 } ?: base.targetWeight
    val nextReps = when (type) {
        ExerciseType.STRENGTH, ExerciseType.REPS_DURATION, ExerciseType.REPS_DISTANCE ->
            update.reps?.takeIf { it > 0 } ?: base.targetReps ?: 10
        else -> null
    }
    val nextDuration = when (type) {
        ExerciseType.DURATION, ExerciseType.REPS_DURATION, ExerciseType.DISTANCE_DURATION ->
            update.durationSeconds?.takeIf { it > 0 } ?: base.targetDuration
        else -> null
    }
    val nextDistance = when (type) {
        ExerciseType.DISTANCE, ExerciseType.REPS_DISTANCE, ExerciseType.DISTANCE_DURATION ->
            update.distance?.takeIf { it > 0.0 } ?: base.targetDistance
        else -> null
    }
    val nextUnit = if (update.weight != null || update.distance != null) unit else base.weightUnit

    val updatedWorking = List(targetCount) { idx ->
        val prev = working.getOrNull(idx)
        DetailSpec(
            targetWeight = update.weight?.takeIf { it > 0.0 } ?: prev?.targetWeight ?: nextWeight,
            targetReps = if (update.reps != null) nextReps else (prev?.targetReps ?: nextReps),
            targetDuration = if (update.durationSeconds != null) nextDuration else (prev?.targetDuration ?: nextDuration),
            targetDistance = if (update.distance != null) nextDistance else (prev?.targetDistance ?: nextDistance),
            setType = prev?.setType ?: SetType.NORMAL,
            weightUnit = if (update.weight != null || update.distance != null) nextUnit else (prev?.weightUnit ?: nextUnit),
        )
    }
    return warmups + updatedWorking
}

private fun findItemIndex(items: List<MutableRoutineItem>, query: String): Int {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || items.isEmpty()) return -1
    val exactIdx = items.indexOfFirst { it.exercise.name.equals(trimmed, ignoreCase = true) }
    if (exactIdx >= 0) return exactIdx

    val norm = ExerciseMatcher.normalize(trimmed)
    val normIdx = items.indexOfFirst { ExerciseMatcher.normalize(it.exercise.name) == norm }
    if (normIdx >= 0) return normIdx

    val exercises = items.map { it.exercise }
    val match = ExerciseMatcher.resolve(trimmed, exercises)
    return when (match) {
        is ExerciseMatchResult.Matched -> items.indexOfFirst { it.exercise.id == match.exercise.id }
        is ExerciseMatchResult.Ambiguous -> items.indexOfFirst { it.exercise.id == match.candidates.first().id }
        is ExerciseMatchResult.NotFound ->
            items.indexOfFirst { it.exercise.name.contains(trimmed, ignoreCase = true) }
    }
}

private fun matchPrograms(programs: List<Program>, query: String): List<Program> {
    val exact = programs.filter { it.name.equals(query, ignoreCase = true) }
    if (exact.isNotEmpty()) return exact
    val norm = ExerciseMatcher.normalize(query)
    val normExact = programs.filter { ExerciseMatcher.normalize(it.name) == norm }
    if (normExact.isNotEmpty()) return normExact
    return programs.filter { it.name.contains(query, ignoreCase = true) }
}

private fun matchRoutines(routines: List<Routine>, query: String): List<Routine> {
    val exact = routines.filter { it.name.equals(query, ignoreCase = true) }
    if (exact.isNotEmpty()) return exact
    val norm = ExerciseMatcher.normalize(query)
    val normExact = routines.filter { ExerciseMatcher.normalize(it.name) == norm }
    if (normExact.isNotEmpty()) return normExact
    return routines.filter { it.name.contains(query, ignoreCase = true) }
}

private fun inferExerciseType(input: AiExerciseInput): String = when {
    input.durationSeconds != null && input.distance != null -> ExerciseType.DISTANCE_DURATION.value
    input.durationSeconds != null && input.reps != null -> ExerciseType.REPS_DURATION.value
    input.durationSeconds != null -> ExerciseType.DURATION.value
    input.distance != null && input.reps != null -> ExerciseType.REPS_DISTANCE.value
    input.distance != null -> ExerciseType.DISTANCE.value
    else -> ExerciseType.STRENGTH.value
}

private fun inferBodyPart(name: String, input: AiExerciseInput): String {
    val lower = name.lowercase()
    return when {
        "bench" in lower || "chest" in lower || "pec" in lower || "push-up" in lower || "pushup" in lower -> "chest"
        "row" in lower || "pulldown" in lower || "pull-up" in lower || "pullup" in lower || "chin" in lower || "lat " in lower -> "back"
        "squat" in lower || "lunge" in lower || "leg press" in lower || "leg extension" in lower || "split squat" in lower -> "thighs"
        "deadlift" in lower || "leg curl" in lower || "nordic" in lower || "hamstring" in lower -> "hamstrings"
        "hip thrust" in lower || "glute" in lower || "kickback" in lower -> "glutes"
        "calf" in lower || "calves" in lower -> "calves"
        "shoulder" in lower || "overhead press" in lower || "lateral raise" in lower || "face pull" in lower || "delt" in lower -> "shoulders"
        "bicep" in lower || "curl" in lower -> "biceps"
        "tricep" in lower || "pushdown" in lower || "skull crusher" in lower || "dip" in lower -> "triceps"
        "forearm" in lower || "wrist" in lower -> "forearms"
        "plank" in lower || "crunch" in lower || "ab " in lower || "abs" in lower || "core" in lower || "pallof" in lower -> "core"
        else -> ExerciseCatalog.normalizeBodyPart(input.bodyPart)
    }
}

private fun inferEquipment(name: String): String? {
    val lower = name.lowercase()
    return when {
        "barbell" in lower || "ez-bar" in lower || "ez bar" in lower -> "barbell"
        "dumbbell" in lower -> "dumbbell"
        "cable" in lower -> "cable"
        "smith" in lower -> "smith machine"
        "machine" in lower -> "machine"
        "kettlebell" in lower -> "kettlebell"
        "band" in lower -> "band"
        "bodyweight" in lower || "body weight" in lower -> "body weight"
        else -> null
    }
}

private fun cleanExerciseName(raw: String): String? =
    raw.filter { !it.isISOControl() }
        .trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(100)
        .takeIf { it.isNotBlank() }

private fun cleanNamedEntity(raw: String): String? =
    filterProgramName(raw).trim().takeIf { it.isNotBlank() }

private fun ExerciseEntity.toDomainModel() = Exercise(
    id = id,
    name = name,
    exerciseType = exerciseType,
    bodyPart = bodyPart,
    equipment = equipment,
    builtin = builtin,
    otherBodyParts = otherBodyParts.orEmpty().filter { it.isNotBlank() },
)
