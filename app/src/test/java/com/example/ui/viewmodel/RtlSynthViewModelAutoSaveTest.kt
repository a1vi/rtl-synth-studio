package com.example.ui.viewmodel

import androidx.lifecycle.ViewModelStore
import com.example.data.local.LocalDraftSnapshot
import com.example.data.local.LocalWorkspaceStore
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.TargetLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class RtlSynthViewModelAutoSaveTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun periodicAndDebouncedAutoSave_whenEdited_persistsDraftAndSkipsRedundantWrites() =
    runTest(testDispatcher, timeout = 15.seconds) {
      val tempDir = Files.createTempDirectory("autosave_local_1").toFile()
      val vmStore = ViewModelStore()
      try {
        var fakeClockMs = 1_700_000_000_000L
        val localStore = LocalWorkspaceStore(tempDir)
        val vm = RtlSynthViewModel(
          localStore = localStore,
          autoSaveIntervalMs = 5_000L,
          autoSaveDebounceMs = 1_500L,
          clockMillisProvider = { fakeClockMs },
        )
        vmStore.put("vm", vm)

        // Allow initial parse + synthesis to run without user edits
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(AutoSaveStatus.IDLE, vm.workbench.value.autoSaveStatus)

        // Make an RTL edit -> debounced auto-save fires after 1.5s
        fakeClockMs += 60_000L
        vm.updateVerilogCode("module edited_mod(input a, output y); assign y = ~a; endmodule")
        advanceTimeBy(1_600L)
        runCurrent()

        val draft1 = localStore.loadActiveDraft()
        assertNotNull(draft1)
        assertTrue(draft1!!.verilogCode.contains("edited_mod"))
        assertEquals(fakeClockMs, draft1.savedAtEpochMs)
        assertEquals(AutoSaveStatus.SAVED, vm.workbench.value.autoSaveStatus)
        assertNotNull(vm.workbench.value.lastAutoSavedLabel)

        // Advancing past multiple periodic intervals without further edits skips redundant writes
        val savedTimestampBeforeIdle = draft1.savedAtEpochMs
        fakeClockMs += 120_000L
        advanceTimeBy(12_000L)
        runCurrent()
        assertEquals(savedTimestampBeforeIdle, localStore.loadActiveDraft()?.savedAtEpochMs)

        // Second edit -> periodic or debounce saves updated clock period
        fakeClockMs += 60_000L
        vm.updateClockPeriodNs(3.5)
        advanceTimeBy(5_100L)
        runCurrent()

        val draft2 = localStore.loadActiveDraft()
        assertNotNull(draft2)
        assertEquals(3.5, draft2!!.clockPeriodNs, 0.001)
        assertEquals(fakeClockMs, draft2.savedAtEpochMs)
      } finally {
        vmStore.clear()
        tempDir.deleteRecursively()
      }
    }

  @Test
  fun flushAutoSave_onInterruption_persistsDraftAndUpdatesActiveSavedDesign() =
    runTest(testDispatcher, timeout = 15.seconds) {
      val tempDir = Files.createTempDirectory("autosave_local_2").toFile()
      val vmStore = ViewModelStore()
      try {
        val localStore = LocalWorkspaceStore(tempDir)
        val initialDesign = RtlDesign(
          id = "local_design_42",
          name = "adder_4bit",
          description = "4-bit ripple adder",
          verilogCode = "module adder_4bit(input [3:0] a, b, output [3:0] s); assign s = a + b; endmodule",
          targetLibrary = TargetLibrary.ASAP7_7NM.id,
          optimizationGoal = OptimizationGoal.BALANCED.id,
          clockPeriodNs = 2.0,
          createdAtMillis = 1_690_000_000_000L,
        )
        localStore.upsertLocalDesign(initialDesign)

        val vm = RtlSynthViewModel(
          localStore = localStore,
          autoSaveIntervalMs = 60_000L,
          autoSaveDebounceMs = 30_000L,
          clockMillisProvider = { 1_700_000_120_000L },
        )
        vmStore.put("vm", vm)

        advanceTimeBy(100L)
        runCurrent()
        vm.loadSavedDesign(initialDesign)
        advanceTimeBy(100L)
        runCurrent()

        vm.updateVerilogCode("module adder_4bit(input [3:0] a, b, output [3:0] s); assign s = a + b + 1'b1; endmodule")
        // Immediately simulate app going to background (ON_STOP -> flushAutoSave) before debounce fires
        vm.flushAutoSave()

        val draft = localStore.loadActiveDraft()
        assertNotNull(draft)
        assertEquals("local_design_42", draft!!.activeDesignId)
        assertTrue(draft.verilogCode.contains("1'b1"))

        val updatedSaved = localStore.localDesigns.value.firstOrNull { it.id == "local_design_42" }
        assertNotNull(updatedSaved)
        assertTrue(updatedSaved!!.verilogCode.contains("1'b1"))
        assertFalse(vm.workbench.value.hasUnsavedChanges)
        assertEquals(AutoSaveStatus.SAVED, vm.workbench.value.autoSaveStatus)
      } finally {
        vmStore.clear()
        tempDir.deleteRecursively()
      }
    }

  @Test
  fun startupRecovery_restoresLocalDraftAndLinkedActiveDesignId() =
    runTest(testDispatcher, timeout = 15.seconds) {
      val tempDir = Files.createTempDirectory("autosave_local_3").toFile()
      val vmStore = ViewModelStore()
      try {
        val localStore = LocalWorkspaceStore(tempDir)
        val linkedId = "local_des_7"
        localStore.upsertLocalDesign(
          RtlDesign(
            id = linkedId,
            name = "recovered_alu",
            description = "Saved ALU",
            verilogCode = "module recovered_alu(input a, b, output y); assign y = a & b; endmodule",
            createdAtMillis = 1_699_000_000_000L,
          )
        )
        localStore.saveActiveDraft(
          LocalDraftSnapshot(
            activeDesignId = linkedId,
            designName = "recovered_alu",
            description = "Locally auto-saved work",
            verilogCode = "module recovered_alu(input a, b, output y); assign y = a ^ b; endmodule",
            sdcText = "create_clock -name clk -period 1.80 [get_ports a]",
            targetLibrary = TargetLibrary.SKY130_130NM,
            optimizationGoal = OptimizationGoal.MIN_DELAY,
            clockPeriodNs = 1.8,
            savedAtEpochMs = 1_700_000_500_000L,
          )
        )

        val vm = RtlSynthViewModel(
          localStore = localStore,
          autoSaveIntervalMs = 60_000L,
          autoSaveDebounceMs = 30_000L,
        )
        vmStore.put("vm", vm)
        advanceTimeBy(200L)
        runCurrent()

        val state = vm.workbench.value
        assertEquals("recovered_alu", state.designName)
        assertTrue(state.verilogCode.contains("a ^ b"))
        assertEquals(TargetLibrary.SKY130_130NM, state.targetLibrary)
        assertEquals(OptimizationGoal.MIN_DELAY, state.optimizationGoal)
        assertEquals(1.8, state.clockPeriodNs, 0.001)
        assertEquals(linkedId, state.activeDesignId)
        assertEquals(AutoSaveStatus.SAVED, state.autoSaveStatus)
        assertNotNull(state.lastAutoSavedLabel)
      } finally {
        vmStore.clear()
        tempDir.deleteRecursively()
      }
    }
}
