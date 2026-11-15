const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-88d9422c-caba-4d14-b00c-5a4445ef96fa";
const SENIOR_UID = "senior_123";
const CAREGIVER_UID = "caregiver_456";
const STRANGER_UID = "stranger_789";
const PIN_HASH = "a".repeat(64);
const pastDate = () => new Date(Date.now() - 1000);

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

function dbFor(uid) {
  return uid
    ? testEnv.authenticatedContext(uid).firestore(DATABASE_ID)
    : testEnv.unauthenticatedContext().firestore(DATABASE_ID);
}

test("Unauthenticated user cannot read or list careCircles or inviteCodes", async () => {
  const unauth = dbFor(null);
  await assertFails(unauth.collection("careCircles").get());
  await assertFails(unauth.collection("inviteCodes").doc("KG1234").get());
});

test("Authenticated user cannot list inviteCodes (prevents code scraping)", async () => {
  const stranger = dbFor(STRANGER_UID);
  await assertFails(stranger.collection("inviteCodes").get());
});

test("Senior can create a valid CareCircle, inviteCode, and subcollection tasks", async () => {
  const senior = dbFor(SENIOR_UID);
  const circleRef = senior.collection("careCircles").doc("circle_1");

  await assertSucceeds(
    circleRef.set({
      ownerUid: SENIOR_UID,
      caregiverUids: [],
      memberUids: [SENIOR_UID],
      inviteCode: "KG4829",
      caregiverPinHash: PIN_HASH,
      seniorName: "Grandpa Raj",
      homeAddress: "123 Maple Street",
      homeLat: 37.7749,
      homeLng: -122.4194,
      emergencyContactName: "Aarav",
      emergencyContactPhone: "555-0100",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    senior.collection("inviteCodes").doc("KG4829").set({
      circleId: "circle_1",
      ownerUid: SENIOR_UID,
      caregiverPinHash: PIN_HASH,
      createdAt: pastDate(),
    })
  );

  await assertSucceeds(
    circleRef.collection("tasks").doc("task_1").set({
      memberUids: [SENIOR_UID],
      authorUid: SENIOR_UID,
      title: "Drink warm water",
      category: "Wellness",
      dueTimeLabel: "9:00 AM",
      completed: false,
      createdBy: "ai",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    circleRef.collection("tasks").where("memberUids", "array-contains", SENIOR_UID).get()
  );
});

test("Stranger cannot read or query another CareCircle or inject ghost fields", async () => {
  const senior = dbFor(SENIOR_UID);
  const circleRef = senior.collection("careCircles").doc("circle_2");
  await assertSucceeds(
    circleRef.set({
      ownerUid: SENIOR_UID,
      caregiverUids: [],
      memberUids: [SENIOR_UID],
      inviteCode: "KG9999",
      caregiverPinHash: PIN_HASH,
      seniorName: "Grandma Meera",
      homeAddress: "45 Oak Ave",
      homeLat: 37.77,
      homeLng: -122.41,
      emergencyContactName: "Priya",
      emergencyContactPhone: "555-0199",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );

  const stranger = dbFor(STRANGER_UID);
  await assertFails(stranger.collection("careCircles").doc("circle_2").get());
  await assertFails(stranger.collection("careCircles").get());

  // Shadow/ghost field injection rejected
  await assertFails(
    senior.collection("careCircles").doc("circle_3").set({
      ownerUid: SENIOR_UID,
      caregiverUids: [],
      memberUids: [SENIOR_UID],
      inviteCode: "KG1111",
      caregiverPinHash: PIN_HASH,
      seniorName: "Test",
      homeAddress: "",
      homeLat: 0.0,
      homeLng: 0.0,
      emergencyContactName: "",
      emergencyContactPhone: "",
      isAdminOverride: true,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("Caregiver can join a CareCircle with valid invite code and create remote task", async () => {
  const senior = dbFor(SENIOR_UID);
  const circleRef = senior.collection("careCircles").doc("circle_join");
  await assertSucceeds(
    circleRef.set({
      ownerUid: SENIOR_UID,
      caregiverUids: [],
      memberUids: [SENIOR_UID],
      inviteCode: "KG7788",
      caregiverPinHash: PIN_HASH,
      seniorName: "Grandpa",
      homeAddress: "Home",
      homeLat: 37.77,
      homeLng: -122.41,
      emergencyContactName: "Son",
      emergencyContactPhone: "555-0123",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
  await assertSucceeds(
    senior.collection("inviteCodes").doc("KG7788").set({
      circleId: "circle_join",
      ownerUid: SENIOR_UID,
      caregiverPinHash: PIN_HASH,
      createdAt: pastDate(),
    })
  );

  const caregiver = dbFor(CAREGIVER_UID);
  await assertSucceeds(caregiver.collection("inviteCodes").doc("KG7788").get());
  await assertSucceeds(
    caregiver.collection("careCircles").doc("circle_join").update({
      caregiverUids: [CAREGIVER_UID],
      memberUids: [SENIOR_UID, CAREGIVER_UID],
      updatedAt: pastDate(),
    })
  );

  await assertSucceeds(
    caregiver.collection("careCircles").doc("circle_join").collection("tasks").doc("cg_task").set({
      memberUids: [SENIOR_UID, CAREGIVER_UID],
      authorUid: CAREGIVER_UID,
      title: "Call Aarav at 5 PM",
      category: "Family",
      dueTimeLabel: "5:00 PM",
      completed: false,
      createdBy: "caregiver",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});
