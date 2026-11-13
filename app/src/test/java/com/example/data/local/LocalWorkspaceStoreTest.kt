package com.example.data.local

import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.SynthesisRun
import com.example.data.model.TargetLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalWorkspaceStoreTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun localStore_persistsDraftDesignsAndRunsAcrossReinstantiation() {
    val root = tempFolder.newFolder("rtl_local_test")
    val store1 = LocalWorkspaceStore(root)

    val draft = LocalDraftSnapshot(
      designName = "offline_fifo",
      description = "Offline draft test",
      verilogCode = "module offline_fifo(input a, output y); assign y = a; endmodule",
      sdcText = "create_clock -name clk -period 3.00 [get_ports clk]",
      targetLibrary = TargetLibrary.SKY130_130NM,
      optimizationGoal = OptimizationGoal.MIN_AREA,
      clockPeriodNs = 3.0,
    )
    store1.saveActiveDraft(draft)

    val savedId = store1.upsertLocalDesign(
      RtlDesign(
        name = "offline_fifo",
        description = "Offline saved",
        verilogCode = draft.verilogCode,
        targetLibrary = TargetLibrary.SKY130_130NM.id,
        optimizationGoal = OptimizationGoal.MIN_AREA.id,
        clockPeriodNs = 3.0,
        lastWnsNs = 1.4,
        lastAreaUm2 = 42.5,
        lastCellCount = 4L,
        lastPowerMw = 0.08,
      )
    )
    assertTrue(savedId.startsWith("local_"))

    val runId = store1.recordLocalRun(
      SynthesisRun(
        designId = savedId,
        designName = "offline_fifo",
        targetLibrary = TargetLibrary.SKY130_130NM.id,
        optimizationGoal = OptimizationGoal.MIN_AREA.id,
        clockPeriodNs = 3.0,
        fmaxMhz = 625.0,
        wnsNs = 1.4,
        tnsNs = 0.0,
        areaUm2 = 42.5,
        cellCount = 4L,
        lutCount = 3L,
        dffCount = 1L,
        powerMw = 0.08,
      )
    )
    assertTrue(runId.startsWith("run_"))

    // Re-open store from disk to verify persistence across sessions
    val store2 = LocalWorkspaceStore(root)
    val loadedDraft = store2.loadActiveDraft()
    assertNotNull(loadedDraft)
    assertEquals("offline_fifo", loadedDraft?.designName)
    assertEquals(TargetLibrary.SKY130_130NM, loadedDraft?.targetLibrary)
    assertEquals(3.0, loadedDraft?.clockPeriodNs ?: 0.0, 1e-4)

    assertEquals(1, store2.localDesigns.value.size)
    assertEquals(savedId, store2.localDesigns.value.first().id)
    assertEquals(1, store2.localRuns.value.size)
    assertEquals(runId, store2.localRuns.value.first().id)

    store2.deleteLocalDesign(savedId)
    store2.deleteLocalRun(runId)
    assertTrue(store2.localDesigns.value.isEmpty())
    assertTrue(store2.localRuns.value.isEmpty())
  }
}
