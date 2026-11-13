package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.RtlWorkspaceDraft
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RtlDesignRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun createAndObserveDesign_authenticatedOwner_succeeds() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = RtlDesignRepository(firestore)
    val designId = "design_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    val design = RtlDesign(
      id = designId,
      userId = uid,
      name = "alu_8bit",
      description = "8-bit arithmetic logic unit",
      verilogCode = "module alu_8bit(input [7:0] a, b, output [7:0] y); assign y = a + b; endmodule",
      targetLibrary = TargetLibrary.ASAP7_7NM.id,
      optimizationGoal = OptimizationGoal.MIN_DELAY.id,
      clockPeriodNs = 2.5,
      lastWnsNs = 0.85,
      lastAreaUm2 = 14.4,
      lastCellCount = 18L,
      lastPowerMw = 0.31,
    )

    val createResult = withTimeout(DEFAULT_TIMEOUT_MS) { repo.createDesign(design) }
    assertTrue("Expected createDesign success", createResult.isSuccess)
    assertEquals(designId, createResult.getOrThrow())

    val observed = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeDesigns(uid).first { list -> list.any { it.id == designId } }
    }
    val saved = observed.first { it.id == designId }
    assertEquals("alu_8bit", saved.name)
    assertNotNull(saved.createdAt)

    val updateResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.updateDesign(saved.copy(name = "alu_8bit_opt", lastCellCount = 16L))
    }
    assertTrue("Expected updateDesign success", updateResult.isSuccess)

    val deleteResult = withTimeout(DEFAULT_TIMEOUT_MS) { repo.deleteDesign(designId) }
    assertTrue("Expected deleteDesign success", deleteResult.isSuccess)
  }

  @Test
  fun recordAndObserveSynthesisRun_authenticatedOwner_succeeds() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = RtlDesignRepository(firestore)
    val runId = "run_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    val run = SynthesisRun(
      id = runId,
      userId = uid,
      designId = "design_abc",
      designName = "counter_8bit",
      targetLibrary = TargetLibrary.ARTIX7_FPGA.id,
      optimizationGoal = OptimizationGoal.BALANCED.id,
      clockPeriodNs = 10.0,
      fmaxMhz = 312.5,
      wnsNs = 6.8,
      tnsNs = 0.0,
      areaUm2 = 28.0,
      cellCount = 22L,
      lutCount = 14L,
      dffCount = 8L,
      powerMw = 1.45,
      criticalPathSummary = "rst -> LUT6 -> CARRY4 -> FDRE (3.20 ns)",
      netlistVerilog = "module counter_8bit(input clk, output q); endmodule",
    )

    val createRun = withTimeout(DEFAULT_TIMEOUT_MS) { repo.recordSynthesisRun(run) }
    assertTrue("Expected recordSynthesisRun success", createRun.isSuccess)

    val observed = withTimeout(FLOW_TIMEOUT_MS) {
      repo.observeSynthesisRuns(uid).first { list -> list.any { it.id == runId } }
    }
    assertTrue(observed.any { it.id == runId && it.fmaxMhz == 312.5 })

    val deleteRun = withTimeout(DEFAULT_TIMEOUT_MS) { repo.deleteSynthesisRun(runId) }
    assertTrue(deleteRun.isSuccess)
  }

  @Test
  fun observeDesigns_crossUserAccess_failsWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = RtlDesignRepository(firestore)
    val designId = "design_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRepo.createDesign(
        RtlDesign(
          id = designId,
          userId = aliceUid,
          name = "secret_core",
          verilogCode = "module secret_core(input a, output y); assign y = ~a; endmodule",
        )
      ).getOrThrow()
    }

    signInTestUser(BOB_EMAIL)
    val bobRepo = RtlDesignRepository(firestore)
    var caught: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) { bobRepo.observeDesigns(aliceUid).first() }
    } catch (e: Exception) {
      caught = e
    }
    val firestoreException = checkNotNull(caught?.unwrapFirestoreException()) {
      "Expected nested FirebaseFirestoreException, got $caught"
    }
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
  }

  @Test
  fun observeDesigns_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repo = RtlDesignRepository(firestore)
    var caught: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) { repo.observeDesigns("some_user_uid").first() }
    } catch (e: Exception) {
      caught = e
    }
    val firestoreException = checkNotNull(caught?.unwrapFirestoreException()) {
      "Expected nested FirebaseFirestoreException, got $caught"
    }
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
  }

  @Test
  fun saveGetAndDeleteAutoSaveDraft_authenticatedOwner_succeedsAndPreservesCreatedAt() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repo = RtlDesignRepository(firestore)
    val draftId = "autosave_${UUID.randomUUID().toString().replace("-", "").take(8)}"

    val initialDraft = RtlWorkspaceDraft(
      id = draftId,
      userId = uid,
      activeCloudDesignId = "",
      designName = "uart_tx",
      description = "Auto-saved UART draft",
      verilogCode = "module uart_tx(input clk, output tx); assign tx = clk; endmodule",
      sdcText = "create_clock -name clk -period 4.00 [get_ports clk]",
      targetLibrary = TargetLibrary.ASAP7_7NM.id,
      optimizationGoal = OptimizationGoal.BALANCED.id,
      clockPeriodNs = 4.0,
      lastWnsNs = 1.25,
      lastAreaUm2 = 9.6,
      lastCellCount = 6L,
      lastPowerMw = 0.14,
    )

    val createRes = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.saveAutoSaveDraft(initialDraft, knownExists = false, draftId = draftId)
    }
    assertTrue("Expected initial saveAutoSaveDraft create success", createRes.isSuccess)

    val loaded1 = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.getAutoSaveDraft(draftId).getOrThrow()
    }
    assertNotNull(loaded1)
    assertEquals("uart_tx", loaded1?.designName)
    assertEquals("create_clock -name clk -period 4.00 [get_ports clk]", loaded1?.sdcText)
    val createdAt = loaded1?.createdAt
    assertNotNull(createdAt)

    val updateRes = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.saveAutoSaveDraft(
        initialDraft.copy(
          designName = "uart_tx_v2",
          clockPeriodNs = 2.5,
          sdcText = "create_clock -name clk -period 2.50 [get_ports clk]",
          lastCellCount = 12L,
        ),
        knownExists = true,
        draftId = draftId,
      )
    }
    assertTrue("Expected saveAutoSaveDraft update success", updateRes.isSuccess)

    val loaded2 = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.getAutoSaveDraft(draftId).getOrThrow()
    }
    assertEquals("uart_tx_v2", loaded2?.designName)
    assertEquals(2.5, loaded2?.clockPeriodNs ?: 0.0, 0.001)
    assertEquals(12L, loaded2?.lastCellCount)
    assertEquals(createdAt, loaded2?.createdAt)

    val deleteRes = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.deleteAutoSaveDraft(draftId)
    }
    assertTrue(deleteRes.isSuccess)
    val afterDelete = withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.getAutoSaveDraft(draftId).getOrThrow()
    }
    assertNull(afterDelete)
  }

  @Test
  fun autoSaveDraft_unauthenticated_failsOrThrowsPermissionDenied() = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val aliceRepo = RtlDesignRepository(firestore)
    val draftId = "autosave_${UUID.randomUUID().toString().replace("-", "").take(8)}"
    withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRepo.saveAutoSaveDraft(
        RtlWorkspaceDraft(
          designName = "private_wip",
          verilogCode = "module p(input a, output y); assign y = a; endmodule",
        ),
        knownExists = false,
        draftId = draftId,
      ).getOrThrow()
    }

    auth.signOut()
    val unauthRepo = RtlDesignRepository(firestore)
    var caught: Throwable? = null
    try {
      withTimeout(DEFAULT_TIMEOUT_MS) { unauthRepo.getAutoSaveDraft(draftId) }
    } catch (e: Exception) {
      caught = e
    }
    assertTrue(caught is IllegalStateException)
  }

  private companion object {
    const val ALICE_EMAIL = "alice_rtl@test.com"
    const val BOB_EMAIL = "bob_rtl@test.com"
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 3000L
  }
}
