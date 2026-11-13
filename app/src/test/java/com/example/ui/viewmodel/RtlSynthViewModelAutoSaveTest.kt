package com.example.ui.viewmodel

import com.example.data.local.LocalDraftSnapshot
import com.example.data.local.LocalWorkspaceStore
import com.example.data.model.OptimizationGoal
import com.example.data.model.RtlDesign
import com.example.data.model.RtlWorkspaceDraft
import com.example.data.model.TargetLibrary
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

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

  private class FakeWorkspaceAutoSaveGateway(
    var initialDraft: RtlWorkspaceDraft? = null,
    var linkedDesigns: MutableMap<String, RtlDesign> = mutableMapOf(),
  ) : WorkspaceAutoSaveGateway {
    var loadCount = 0
    val savedDrafts = mutableListOf<Pair<RtlWorkspaceDraft, Boolean?>>()
    val updatedDesigns = mutableListOf<RtlDesign>()
    var failNextSave = false

    override suspend fun loadAutoSaveDraft(): Result<RtlWorkspaceDraft?> {
      loadCount++
      return Result.success(initialDraft)
    }

    override suspend fun saveAutoSaveDraft(
      draft: RtlWorkspaceDraft,
      knownExists: Boolean?,
    ): Result<Unit> {
      if (failNextSave) {
        failNextSave = false
        return Result.failure(RuntimeException("Network timeout"))
      }
      savedDrafts += draft to knownExists
      initialDraft = draft
      return Result.success(Unit)
    }

    override suspend fun updateActiveDesign(design: RtlDesign): Result<Unit> {
      updatedDesigns += design
      linkedDesigns[design.id] = design
      return Result.success(Unit)
    }

    override suspend fun getDesignById(designId: String): Result<RtlDesign?> {
      return Result.success(linkedDesigns[designId])
    }
  }

  @Test
  fun periodicAndDebouncedAutoSave_whenEdited_savesDraftToGatewayAndSkipsRedundantWrites() = runTest {
    val tempDir = Files.createTempDirectory("autosave_test_1").toFile()
    try {
      val localStore = LocalWorkspaceStore(tempDir)
      val gateway = FakeWorkspaceAutoSaveGateway()
      val vm = RtlSynthViewModel(
        repository = null,
        currentUserId = "uid_alice",
        localStore = localStore,
        isOfflineMode = false,
        autoSaveGateway = gateway,
        autoSaveIntervalMs = 5_000L,
        autoSaveDebounceMs = 1_500L,
        clockMillisProvider = { 1_700_000_000_000L },
      )

      // Allow startup recovery + initial synthesis to complete without editing
      advanceTimeBy(4_000L)
      assertEquals(1, gateway.loadCount)
      assertEquals(0, gateway.savedDrafts.size)

      // Make an RTL edit -> debounced auto-save fires after 1.5s
      vm.updateVerilogCode("module edited_mod(input a, output y); assign y = ~a; endmodule")
      advanceTimeBy(1_600L)
      assertEquals(1, gateway.savedDrafts.size)
      assertEquals(false, gateway.savedDrafts[0].second) // initial load returned null -> knownExists = false
      assertTrue(gateway.savedDrafts[0].first.verilogCode.contains("edited_mod"))
      assertEquals(AutoSaveStatus.SAVED, vm.workbench.value.autoSaveStatus)
      assertNotNull(vm.workbench.value.lastAutoSavedLabel)

      // Advancing past multiple periodic intervals without further edits produces no redundant writes
      advanceTimeBy(12_000L)
      assertEquals(1, gateway.savedDrafts.size)

      // Second edit -> periodic or debounce saves with knownExists = true
      vm.updateClockPeriodNs(3.5)
      advanceTimeBy(5_100L)
      assertEquals(2, gateway.savedDrafts.size)
      assertEquals(true, gateway.savedDrafts[1].second)
      assertEquals(3.5, gateway.savedDrafts[1].first.clockPeriodNs, 0.001)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun flushAutoSave_onInterruption_persistsDraftAndSyncsActiveCloudDesign() = runTest {
    val tempDir = Files.createTempDirectory("autosave_test_2").toFile()
    try {
      val localStore = LocalWorkspaceStore(tempDir)
      val createdTs = Timestamp(1_690_000_000L, 0)
      val savedDesign = RtlDesign(
        id = "cloud_design_42",
        userId = "uid_alice",
        name = "adder_4bit",
        description = "4-bit ripple adder",
        verilogCode = "module adder_4bit(input [3:0] a, b, output [3:0] s); assign s = a + b; endmodule",
        targetLibrary = TargetLibrary.ASAP7_7NM.id,
        optimizationGoal = OptimizationGoal.BALANCED.id,
        clockPeriodNs = 2.0,
        createdAt = createdTs,
      )
      val gateway = FakeWorkspaceAutoSaveGateway(
        linkedDesigns = mutableMapOf(savedDesign.id to savedDesign),
      )
      val vm = RtlSynthViewModel(
        repository = null,
        currentUserId = "uid_alice",
        localStore = localStore,
        isOfflineMode = false,
        autoSaveGateway = gateway,
        autoSaveIntervalMs = 60_000L,
        autoSaveDebounceMs = 30_000L,
      )

      advanceTimeBy(100L)
      vm.loadSavedDesign(savedDesign)
      advanceTimeBy(100L)

      vm.updateVerilogCode("module adder_4bit(input [3:0] a, b, output [3:0] s); assign s = a + b + 1'b1; endmodule")
      // Immediately simulate app going to background (ON_STOP -> flushAutoSave) before debounce or interval fires
      vm.flushAutoSave()
      advanceTimeBy(100L)

      assertEquals(1, gateway.savedDrafts.size)
      assertEquals("cloud_design_42", gateway.savedDrafts[0].first.activeCloudDesignId)
      assertEquals(1, gateway.updatedDesigns.size)
      assertTrue(gateway.updatedDesigns[0].verilogCode.contains("1'b1"))
      assertFalse(vm.workbench.value.hasUnsavedChanges)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun startupRecovery_restoresCloudDraftWhenNewerThanLocal() = runTest {
    val tempDir = Files.createTempDirectory("autosave_test_3").toFile()
    try {
      val localStore = LocalWorkspaceStore(tempDir)
      localStore.saveActiveDraft(
        LocalDraftSnapshot(
          designName = "older_local",
          description = "stale",
          verilogCode = "module older_local(input a, output y); assign y = a; endmodule",
          sdcText = "",
          targetLibrary = TargetLibrary.ASAP7_7NM,
          optimizationGoal = OptimizationGoal.BALANCED,
          clockPeriodNs = 5.0,
          savedAtEpochMs = 1_600_000_000_000L,
        )
      )

      val cloudDesignId = "cloud_des_7"
      val cloudDraft = RtlWorkspaceDraft(
        id = "autosave",
        userId = "uid_alice",
        activeCloudDesignId = cloudDesignId,
        designName = "recovered_alu",
        description = "Cloud auto-saved work",
        verilogCode = "module recovered_alu(input a, b, output y); assign y = a ^ b; endmodule",
        sdcText = "create_clock -name clk -period 1.80 [get_ports a]",
        targetLibrary = TargetLibrary.SKY130_130NM.id,
        optimizationGoal = OptimizationGoal.MIN_DELAY.id,
        clockPeriodNs = 1.8,
        createdAt = Timestamp(1_700_000_000L, 0),
        updatedAt = Timestamp(1_700_000_500L, 0),
      )
      val gateway = FakeWorkspaceAutoSaveGateway(
        initialDraft = cloudDraft,
        linkedDesigns = mutableMapOf(
          cloudDesignId to RtlDesign(
            id = cloudDesignId,
            userId = "uid_alice",
            name = "recovered_alu",
            createdAt = Timestamp(1_699_000_000L, 0),
          )
        ),
      )

      val vm = RtlSynthViewModel(
        repository = null,
        currentUserId = "uid_alice",
        localStore = localStore,
        isOfflineMode = false,
        autoSaveGateway = gateway,
        autoSaveIntervalMs = 60_000L,
        autoSaveDebounceMs = 30_000L,
      )
      advanceTimeBy(200L)

      val state = vm.workbench.value
      assertEquals("recovered_alu", state.designName)
      assertTrue(state.verilogCode.contains("a ^ b"))
      assertEquals(TargetLibrary.SKY130_130NM, state.targetLibrary)
      assertEquals(OptimizationGoal.MIN_DELAY, state.optimizationGoal)
      assertEquals(1.8, state.clockPeriodNs, 0.001)
      assertEquals(cloudDesignId, state.activeCloudDesignId)
      assertNotNull(state.activeCloudCreatedAt)
      assertEquals(AutoSaveStatus.SAVED, state.autoSaveStatus)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun autoSaveFailure_setsErrorStatusAndRetriesOnNextInterval() = runTest {
    val tempDir = Files.createTempDirectory("autosave_test_4").toFile()
    try {
      val localStore = LocalWorkspaceStore(tempDir)
      val gateway = FakeWorkspaceAutoSaveGateway().apply { failNextSave = true }
      val vm = RtlSynthViewModel(
        repository = null,
        currentUserId = "uid_alice",
        localStore = localStore,
        isOfflineMode = false,
        autoSaveGateway = gateway,
        autoSaveIntervalMs = 5_000L,
        autoSaveDebounceMs = 1_000L,
      )

      advanceTimeBy(100L)
      vm.updateVerilogCode("module retry_mod(input a, output y); assign y = a; endmodule")
      advanceTimeBy(1_100L)

      assertEquals(AutoSaveStatus.ERROR, vm.workbench.value.autoSaveStatus)
      assertEquals(0, gateway.savedDrafts.size)

      // Next periodic tick retries and succeeds
      advanceTimeBy(5_000L)
      assertEquals(AutoSaveStatus.SAVED, vm.workbench.value.autoSaveStatus)
      assertEquals(1, gateway.savedDrafts.size)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun offlineMode_neverCallsCloudGateway() = runTest {
    val tempDir = Files.createTempDirectory("autosave_test_5").toFile()
    try {
      val localStore = LocalWorkspaceStore(tempDir)
      val gateway = FakeWorkspaceAutoSaveGateway()
      val vm = RtlSynthViewModel(
        repository = null,
        currentUserId = "local_offline",
        localStore = localStore,
        isOfflineMode = true,
        autoSaveGateway = gateway,
        autoSaveIntervalMs = 1_000L,
        autoSaveDebounceMs = 500L,
      )

      vm.updateVerilogCode("module offline_mod(input a, output y); assign y = a; endmodule")
      vm.flushAutoSave()
      advanceTimeBy(5_000L)

      assertEquals(AutoSaveStatus.OFFLINE, vm.workbench.value.autoSaveStatus)
      assertEquals(0, gateway.loadCount)
      assertEquals(0, gateway.savedDrafts.size)
      assertTrue(localStore.loadActiveDraft()?.verilogCode?.contains("offline_mod") == true)
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
