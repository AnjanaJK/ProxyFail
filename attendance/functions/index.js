const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();
const db = admin.firestore();

/* ======================================================
   🔔 SEND NOTIFICATION TO STUDENTS (Triggered on Firestore)
========================================================= */
exports.sendScheduledNotification = functions.firestore
  .document("fcmNotifications/{notificationId}")
  .onCreate(async (snap, context) => {
    const data = snap.data();
    const { title, message, scheduledTime, teacherId } = data || {};

    console.log(`📢 New FCM notification created by teacher: ${teacherId}`);

    try {
      // Fetch student tokens
      const tokensSnapshot = await db.collection("deviceTokens").get();
      const tokens = tokensSnapshot.docs.map((doc) => doc.id);

      if (!tokens.length) {
        console.log("⚠️ No device tokens found for students");
        await snap.ref.update({
          status: "failed",
          reason: "no_student_tokens",
        });
        return null;
      }

      const now = Date.now();
      const delay = scheduledTime - now;

      // If within 1 minute window, send immediately
      if (delay <= 60 * 1000) {
        console.log("🕒 Sending immediately...");
        await sendToStudents(tokens, title, message);
        await snap.ref.update({
          status: "sent",
          sentAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      } else {
        // Mark as pending for the scheduler to pick up
        await snap.ref.update({
          status: "pending",
          queuedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        console.log(`⏰ Queued for scheduled delivery at ${new Date(scheduledTime)}`);
      }
    } catch (err) {
      console.error("❌ Failed to process FCM notification:", err);
      await snap.ref.update({ status: "failed", error: err.message });
    }

    return null;
  });

/* ======================================================
   🕒 SCHEDULED DELIVERY (every 5 minutes)
   Sends pending notifications when their time arrives
========================================================= */
exports.checkPendingNotifications = functions.pubsub
  .schedule("every 5 minutes")
  .timeZone("Asia/Kolkata")
  .onRun(async () => {
    const now = Date.now();
    const pending = await db
      .collection("fcmNotifications")
      .where("status", "==", "pending")
      .get();

    if (pending.empty) {
      console.log("✅ No pending notifications");
      return null;
    }

    const tokensSnap = await db.collection("deviceTokens").get();
    const tokens = tokensSnap.docs.map((d) => d.id);
    if (!tokens.length) {
      console.log("⚠️ No student tokens found");
      return null;
    }

    for (const doc of pending.docs) {
      const n = doc.data();
      if (n.scheduledTime <= now) {
        console.log(`📨 Sending scheduled notification: ${n.title}`);
        await sendToStudents(tokens, n.title, n.message);
        await doc.ref.update({
          status: "sent",
          sentAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      }
    }

    console.log(`🔄 Processed ${pending.size} pending notifications`);
    return null;
  });

/* ======================================================
   📬 Helper: Send Notification via FCM
========================================================= */
async function sendToStudents(tokens, title, message) {
  const payload = {
    notification: {
      title: title || "Class Reminder",
      body: message || "You have a new update from your teacher",
    },
    data: {
      click_action: "FLUTTER_NOTIFICATION_CLICK",
      type: "class_reminder",
    },
  };

  try {
    const response = await admin.messaging().sendEachForMulticast({
      tokens,
      ...payload,
    });
    console.log(`✅ Sent to ${response.successCount}/${tokens.length} students`);
  } catch (err) {
    console.error("❌ Error sending FCM message:", err);
  }
}

/* ======================================================
   🔐 SESSION LOGIN / LOGOUT FOR HOSTING
========================================================= */
exports.hostAuth = functions.https.onRequest(async (req, res) => {
  res.set("Access-Control-Allow-Origin", "https://project-proxyfail.web.app");
  res.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
  res.set("Access-Control-Allow-Credentials", "true");

  if (req.method === "OPTIONS") return res.status(200).send("");

  const path = req.path;

  if (path === "/sessionLogin") {
    try {
      const idToken = req.body?.idToken || req.query?.idToken;
      if (!idToken) return res.status(401).send("Missing ID token");

      const expiresIn = 5 * 24 * 60 * 60 * 1000; // 5 days
      const sessionCookie = await admin.auth().createSessionCookie(idToken, { expiresIn });

      const options = {
        maxAge: expiresIn,
        httpOnly: true,
        secure: true,
        path: "/",
        sameSite: "none",
      };

      res.cookie("__session", sessionCookie, options);
      res.redirect("/student-dashboard");
    } catch (error) {
      console.error("Session creation failed:", error);
      res.status(401).send("Unauthorized");
    }
  } else if (path === "/sessionLogout") {
    res.clearCookie("__session", {
      httpOnly: true,
      secure: true,
      path: "/",
      sameSite: "none",
    });
    res.redirect("/");
  } else {
    res.status(404).json({ error: "Endpoint not found" });
  }
});

/* ======================================================
   🧪 TEST SESSION CREATION
========================================================= */
exports.createTestSession = functions.https.onRequest(async (req, res) => {
  const session = {
    courseId: "CS401-FALL25",
    isActive: true,
    requiredBeaconServiceUuid: "12345678-1234-5678-1234-56789abcdef0",
    qrCodeValue: "QR-TEST123",
    teacherId: "TEST_TEACHER_UID",
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    allowedRadiusMeters: 50,
    minRequiredRssi: -85,
    location: new admin.firestore.GeoPoint(19.0760, 72.8777), // Example: Mumbai
  };

  await db.collection("sessions").add(session);
  res.status(200).send("✅ Test session created");
});

/* ======================================================
   🔍 ATTENDANCE VALIDATION
========================================================= */
exports.onAttendanceWrite = functions.firestore
  .document("attendance/{attendanceId}")
  .onCreate(async (snap, context) => {
    const attendance = snap.data();
    if (!attendance) return null;

    try {
      const {
        sessionId,
        scannedQrValue,
        latitude,
        longitude,
        scannedBeaconId,
        beaconRssi,
        mockLocationDetected,
        deviceIntegrity,
        studentId: submittedStudentId,
      } = attendance;

      const sessionSnap = await db.collection("sessions").doc(sessionId).get();
      if (!sessionSnap.exists) {
        await snap.ref.update({ status: "rejected", reason: "session_not_found" });
        return null;
      }

      const session = sessionSnap.data();
      const now = Date.now();
      const createdAt = session.createdAt?.toMillis?.() || 0;
      const expiresAt = session.expiresAt?.toMillis?.() || createdAt + 2 * 60 * 60 * 1000;

      if (scannedQrValue !== session.qrCodeValue)
        return snap.ref.update({ status: "rejected", reason: "invalid_qr" });

      if (now < createdAt || now > expiresAt)
        return snap.ref.update({ status: "rejected", reason: "session_expired" });

      if (mockLocationDetected || deviceIntegrity !== true)
        return snap.ref.update({ status: "rejected", reason: "device_or_mock_violation" });

      const distance = haversineDistanceMeters(
        session.location.latitude,
        session.location.longitude,
        latitude,
        longitude
      );
      if (distance > (session.allowedRadiusMeters || 50))
        return snap.ref.update({
          status: "rejected",
          reason: "out_of_range",
          distanceMeters: Math.round(distance),
        });

      const studentId = context.auth?.uid || submittedStudentId;
      if (!studentId)
        return snap.ref.update({ status: "rejected", reason: "missing_student_id" });

      await snap.ref.update({
        status: "present",
        verifiedAt: admin.firestore.FieldValue.serverTimestamp(),
        distanceMeters: Math.round(distance),
        studentId,
        submittedRssi: beaconRssi || null,
      });

      console.log(`✅ Verified attendance for ${studentId}`);
    } catch (err) {
      console.error("🔥 Error verifying attendance:", err);
      await snap.ref.update({
        status: "rejected",
        reason: "server_error",
        errorDetails: err.message,
      });
    }
    return null;
  });

/* ======================================================
   🔁 SESSION QR ROTATION (Every 5 Minutes)
========================================================= */
exports.rotateActiveSessions = functions.pubsub
  .schedule("every 5 minutes")
  .timeZone("Asia/Kolkata")
  .onRun(async () => {
    const activeSessions = await db
      .collection("sessions")
      .where("isActive", "==", true)
      .get();
    if (activeSessions.empty) return null;

    const batch = db.batch();
    const now = admin.firestore.Timestamp.now();
    const expiry = new Date(now.toDate().getTime() + 5 * 60 * 1000);
    const expiresAt = admin.firestore.Timestamp.fromDate(expiry);

    activeSessions.forEach((doc) => {
      const newQr = Math.random().toString(36).substring(2, 10).toUpperCase();
      batch.update(doc.ref, {
        qrCodeValue: newQr,
        expiresAt,
        lastRotated: now,
      });
    });

    await batch.commit();
    console.log(`🔄 Rotated ${activeSessions.size} active sessions`);
    return null;
  });

/* ======================================================
   🧹 CLEANUP STALE SESSIONS (Every 30 min)
========================================================= */
exports.cleanupStaleSessions = functions.pubsub
  .schedule("every 30 minutes")
  .timeZone("Asia/Kolkata")
  .onRun(async () => {
    const cutoff = admin.firestore.Timestamp.fromDate(
      new Date(Date.now() - 2 * 60 * 60 * 1000)
    );
    const stale = await db
      .collection("sessions")
      .where("isActive", "==", true)
      .where("createdAt", "<", cutoff)
      .get();

    if (stale.empty) return null;

    const batch = db.batch();
    stale.forEach((doc) =>
      batch.update(doc.ref, {
        isActive: false,
        endedAt: admin.firestore.FieldValue.serverTimestamp(),
        autoEnded: true,
      })
    );

    await batch.commit();
    console.log(`🧹 Cleaned up ${stale.size} stale sessions`);
    return null;
  });

/* ======================================================
   📏 Helper: Distance Calculation
========================================================= */
function haversineDistanceMeters(lat1, lon1, lat2, lon2) {
  const toRad = (v) => (v * Math.PI) / 180;
  const R = 6371000;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
