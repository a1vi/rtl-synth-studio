package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.RtlDesign
import com.example.data.model.RtlWorkspaceDraft
import com.example.data.model.SynthesisRun
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class RtlDesignRepository(private val db: FirebaseFirestore) {

  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private val auth = Firebase.auth

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
  }

  fun observeDesigns(userId: String): Flow<List<RtlDesign>> = flow {
    val path = "users/$userId/designs"
    emitAll(
      db.collection("users").document(userId).collection("designs")
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(RtlDesign::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  fun observeSynthesisRuns(userId: String): Flow<List<SynthesisRun>> = flow {
    val path = "users/$userId/synthesisRuns"
    emitAll(
      db.collection("users").document(userId).collection("synthesisRuns")
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(SynthesisRun::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  suspend fun getDesignById(designId: String): Result<RtlDesign?> {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid).collection("designs").document(designId)
    return try {
      val snapshot = docRef.get().await()
      Result.success(
        snapshot.toObject(RtlDesign::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun createDesign(design: RtlDesign): Result<String> {
    val uid = requireUserId()
    val colRef = db.collection("users").document(uid).collection("designs")
    val docRef = if (design.id.isNotBlank()) colRef.document(design.id) else colRef.document()
    val payload = mapOf(
      "userId" to uid,
      "name" to design.name.trim().take(80),
      "description" to design.description.trim().take(300),
      "verilogCode" to design.verilogCode.take(20000),
      "targetLibrary" to design.targetLibrary,
      "optimizationGoal" to design.optimizationGoal,
      "clockPeriodNs" to design.clockPeriodNs.coerceIn(0.1, 1000.0),
      "lastWnsNs" to design.lastWnsNs.coerceIn(-1000.0, 1000.0),
      "lastAreaUm2" to design.lastAreaUm2.coerceIn(0.0, 1000000.0),
      "lastCellCount" to design.lastCellCount.coerceIn(0L, 100000L),
      "lastPowerMw" to design.lastPowerMw.coerceIn(0.0, 10000.0),
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    return try {
      docRef.set(payload).await()
      Result.success(docRef.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateDesign(design: RtlDesign): Result<Unit> {
    val uid = requireUserId()
    require(design.id.isNotBlank()) { "Design ID must not be blank for update" }
    requireNotNull(design.createdAt) { "Design createdAt must not be null for update" }
    val docRef = db.collection("users").document(uid).collection("designs").document(design.id)
    return try {
      docRef.set(
        design.copy(
          userId = uid,
          name = design.name.trim().take(80),
          description = design.description.trim().take(300),
          verilogCode = design.verilogCode.take(20000),
          clockPeriodNs = design.clockPeriodNs.coerceIn(0.1, 1000.0),
          lastWnsNs = design.lastWnsNs.coerceIn(-1000.0, 1000.0),
          lastAreaUm2 = design.lastAreaUm2.coerceIn(0.0, 1000000.0),
          lastCellCount = design.lastCellCount.coerceIn(0L, 100000L),
          lastPowerMw = design.lastPowerMw.coerceIn(0.0, 10000.0),
          updatedAt = null,
        )
      ).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun deleteDesign(designId: String): Result<Unit> {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid).collection("designs").document(designId)
    return try {
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun recordSynthesisRun(run: SynthesisRun): Result<String> {
    val uid = requireUserId()
    val colRef = db.collection("users").document(uid).collection("synthesisRuns")
    val docRef = if (run.id.isNotBlank()) colRef.document(run.id) else colRef.document()
    val payload = mapOf(
      "userId" to uid,
      "designId" to run.designId.take(128),
      "designName" to run.designName.trim().take(80),
      "targetLibrary" to run.targetLibrary,
      "optimizationGoal" to run.optimizationGoal,
      "clockPeriodNs" to run.clockPeriodNs.coerceIn(0.1, 1000.0),
      "fmaxMhz" to run.fmaxMhz.coerceIn(0.0, 100000.0),
      "wnsNs" to run.wnsNs.coerceIn(-1000.0, 1000.0),
      "tnsNs" to run.tnsNs.coerceIn(-100000.0, 1000.0),
      "areaUm2" to run.areaUm2.coerceIn(0.0, 1000000.0),
      "cellCount" to run.cellCount.coerceIn(0L, 100000L),
      "lutCount" to run.lutCount.coerceIn(0L, 100000L),
      "dffCount" to run.dffCount.coerceIn(0L, 100000L),
      "powerMw" to run.powerMw.coerceIn(0.0, 10000.0),
      "criticalPathSummary" to run.criticalPathSummary.take(500),
      "netlistVerilog" to run.netlistVerilog.take(40000),
      "createdAt" to FieldValue.serverTimestamp(),
    )
    return try {
      docRef.set(payload).await()
      Result.success(docRef.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun deleteSynthesisRun(runId: String): Result<Unit> {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid).collection("synthesisRuns").document(runId)
    return try {
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun getAutoSaveDraft(draftId: String = DEFAULT_AUTOSAVE_ID): Result<RtlWorkspaceDraft?> {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid).collection("workspace").document(draftId)
    return try {
      val snapshot = docRef.get().await()
      Result.success(
        snapshot.toObject(RtlWorkspaceDraft::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun saveAutoSaveDraft(
    draft: RtlWorkspaceDraft,
    knownExists: Boolean? = null,
    draftId: String = DEFAULT_AUTOSAVE_ID,
  ): Result<Unit> {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid).collection("workspace").document(draftId)
    val updatePayload = mapOf(
      "activeCloudDesignId" to draft.activeCloudDesignId.take(128),
      "designName" to draft.designName.trim().take(80),
      "description" to draft.description.trim().take(300),
      "verilogCode" to draft.verilogCode.take(20000),
      "sdcText" to draft.sdcText.take(10000),
      "targetLibrary" to draft.targetLibrary,
      "optimizationGoal" to draft.optimizationGoal,
      "clockPeriodNs" to draft.clockPeriodNs.coerceIn(0.1, 1000.0),
      "lastWnsNs" to draft.lastWnsNs.coerceIn(-1000.0, 1000.0),
      "lastAreaUm2" to draft.lastAreaUm2.coerceIn(0.0, 1000000.0),
      "lastCellCount" to draft.lastCellCount.coerceIn(0L, 100000L),
      "lastPowerMw" to draft.lastPowerMw.coerceIn(0.0, 10000.0),
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    val createPayload = updatePayload + mapOf(
      "userId" to uid,
      "createdAt" to FieldValue.serverTimestamp(),
    )

    return try {
      val exists = knownExists ?: docRef.get().await().exists()
      if (exists) {
        try {
          docRef.update(updatePayload).await()
        } catch (notFound: FirebaseFirestoreException) {
          if (notFound.code == FirebaseFirestoreException.Code.NOT_FOUND) {
            docRef.set(createPayload).await()
          } else {
            throw notFound
          }
        }
      } else {
        docRef.set(createPayload).await()
      }
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val op = if (knownExists == true) OperationType.UPDATE else OperationType.CREATE
      handleFirestoreError(e, op, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun deleteAutoSaveDraft(draftId: String = DEFAULT_AUTOSAVE_ID): Result<Unit> {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid).collection("workspace").document(draftId)
    return try {
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      Result.failure(e)
    }
  }

  companion object {
    const val DEFAULT_AUTOSAVE_ID = "autosave"
  }
}
