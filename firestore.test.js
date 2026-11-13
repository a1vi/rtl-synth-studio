const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validDesignPayload(uid, ts) {
  return {
    userId: uid,
    name: "counter_8bit",
    description: "8-bit synchronous counter",
    verilogCode: "module counter_8bit(input clk, input rst, output reg [7:0] count);\n  always @(posedge clk) if (rst) count <= 0; else count <= count + 1;\nendmodule",
    targetLibrary: "ASAP7_7NM",
    optimizationGoal: "BALANCED",
    clockPeriodNs: 5.0,
    lastWnsNs: 1.4,
    lastAreaUm2: 18.2,
    lastCellCount: 24,
    lastPowerMw: 0.42,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validRunPayload(uid, designId, ts) {
  return {
    userId: uid,
    designId: designId,
    designName: "counter_8bit",
    targetLibrary: "ASAP7_7NM",
    optimizationGoal: "MIN_DELAY",
    clockPeriodNs: 5.0,
    fmaxMhz: 277.8,
    wnsNs: 1.4,
    tnsNs: 0.0,
    areaUm2: 18.2,
    cellCount: 24,
    lutCount: 16,
    dffCount: 8,
    powerMw: 0.42,
    criticalPathSummary: "rst -> INV -> AND2 -> DFFR (3.60 ns)",
    netlistVerilog: "module counter_8bit(clk, rst, count);\nendmodule",
    createdAt: ts,
  };
}

function validWorkspaceDraftPayload(uid, ts) {
  return {
    userId: uid,
    activeCloudDesignId: "",
    designName: "counter_8bit",
    description: "Work in progress",
    verilogCode: "module counter_8bit(input clk, output reg q); always @(posedge clk) q <= ~q; endmodule",
    sdcText: "create_clock -name clk -period 5.00 [get_ports clk]",
    targetLibrary: "ASAP7_7NM",
    optimizationGoal: "BALANCED",
    clockPeriodNs: 5.0,
    lastWnsNs: 1.2,
    lastAreaUm2: 12.5,
    lastCellCount: 8,
    lastPowerMw: 0.18,
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write designs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("designs").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("synthesisRuns").get());
});

test("Authenticated owner can create, read, update, and delete their design", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 2000);
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("designs").doc("design_1");

  await assertSucceeds(docRef.set(validDesignPayload(ALICE_UID, ts)));
  await assertSucceeds(docRef.get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("designs").get());
  await assertSucceeds(docRef.update({
    name: "counter_8bit_v2",
    updatedAt: new Date(Date.now() - 1000),
  }));
  await assertSucceeds(docRef.delete());
});

test("Cross-user access to designs and runs is denied", async () => {
  const ts = new Date(Date.now() - 2000);
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceDoc = aliceDb.collection("users").doc(ALICE_UID).collection("designs").doc("design_1");
  await assertSucceeds(aliceDoc.set(validDesignPayload(ALICE_UID, ts)));

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("designs").doc("design_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("designs").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("designs").doc("design_2").set(
    validDesignPayload(BOB_UID, ts)
  ));
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("designs").doc("design_1").delete());
});

test("Shadow fields, invalid enums, and immutable field mutation are rejected", async () => {
  const ts = new Date(Date.now() - 2000);
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const designsCol = aliceDb.collection("users").doc(ALICE_UID).collection("designs");

  await assertFails(designsCol.doc("bad_ghost").set({
    ...validDesignPayload(ALICE_UID, ts),
    isAdmin: true,
  }));

  await assertFails(designsCol.doc("bad_lib").set({
    ...validDesignPayload(ALICE_UID, ts),
    targetLibrary: "TSMC_3NM",
  }));

  const goodRef = designsCol.doc("design_good");
  await assertSucceeds(goodRef.set(validDesignPayload(ALICE_UID, ts)));
  await assertFails(goodRef.update({
    userId: BOB_UID,
    updatedAt: new Date(Date.now() - 1000),
  }));
});

test("Synthesis runs are create/read/delete only (updates rejected)", async () => {
  const ts = new Date(Date.now() - 2000);
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const runRef = aliceDb.collection("users").doc(ALICE_UID).collection("synthesisRuns").doc("run_1");

  await assertSucceeds(runRef.set(validRunPayload(ALICE_UID, "design_1", ts)));
  await assertSucceeds(runRef.get());
  await assertFails(runRef.update({ wnsNs: 99.0 }));
  await assertSucceeds(runRef.delete());
});

test("Workspace auto-save draft is strictly owner-only and enforces schema invariants", async () => {
  const ts = new Date(Date.now() - 3000);
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("workspace").doc("autosave").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("workspace").doc("autosave")
      .set(validWorkspaceDraftPayload(ALICE_UID, ts))
  );

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const draftRef = aliceDb.collection("users").doc(ALICE_UID).collection("workspace").doc("autosave");

  await assertSucceeds(draftRef.set(validWorkspaceDraftPayload(ALICE_UID, ts)));
  await assertSucceeds(draftRef.get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("workspace").get());

  // Valid update preserves createdAt and userId
  await assertSucceeds(draftRef.update({
    designName: "counter_8bit_wip",
    sdcText: "create_clock -name clk -period 4.00 [get_ports clk]",
    clockPeriodNs: 4.0,
    updatedAt: new Date(Date.now() - 1000),
  }));

  // Immutable createdAt and userId cannot be mutated
  await assertFails(draftRef.update({
    userId: BOB_UID,
    updatedAt: new Date(Date.now() - 500),
  }));
  await assertFails(draftRef.update({
    createdAt: new Date(Date.now() - 500),
    updatedAt: new Date(Date.now() - 500),
  }));

  // Shadow fields and oversized strings rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("workspace").doc("bad_shadow").set({
      ...validWorkspaceDraftPayload(ALICE_UID, ts),
      extraField: "not_allowed",
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("workspace").doc("bad_sdc").set({
      ...validWorkspaceDraftPayload(ALICE_UID, ts),
      sdcText: "x".repeat(10001),
    })
  );

  // Cross-user access denied
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("workspace").doc("autosave").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("workspace").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("workspace").doc("autosave").update({
      designName: "hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("workspace").doc("autosave").delete());

  await assertSucceeds(draftRef.delete());
});
