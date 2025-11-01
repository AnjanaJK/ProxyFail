const functions = require('firebase-functions');
const admin = require('firebase-admin');
// 🆕 NEW: Import the Firestore event type from the Cloud Functions SDK
const { onDocumentCreated } = require('firebase-functions/v2/firestore');
// 🆕 NEW: Import the Pub/Sub event type from the Cloud Functions SDK
const { onSchedule } = require('firebase-functions/v2/scheduler');

admin.initializeApp();

const db = admin.firestore();
// --- Utility: Haversine Distance (UNCHANGED) ---
function haversineDistanceMeters(lat1, lon1, lat2, lon2) {
  const toRad = (v) => (v * Math.PI) / 180;
  const R = 6371000; // Earth's radius in meters
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat/2) * Math.sin(dLat/2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
    Math.sin(dLon/2) * Math.sin(dLon/2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
  return R * c;
}

// ====================================================================
// 1. ATTENDANCE VERIFICATION FUNCTION (FIXED SYNTAX)
// ====================================================================
// ❌ Old Syntax (1st Gen): exports.onAttendanceWrite = functions.firestore.document('attendance/{attendanceId}').onCreate(...)
// ✅ New Syntax (2nd Gen/Modern):
exports.onAttendanceWrite = onDocumentCreated('attendance/{attendanceId}', async (event) => {
    // The data is now accessed via event.data
    const snap = event.data;
    // Exit immediately if snap is null (e.g. deletion)
    if (!snap) return null; 
    
    const attendance = snap.data();
    // The attendanceId parameter is now event.params.attendanceId
    const attendanceId = event.params.attendanceId;

    try {
      const { 
        sessionId, scannedQrValue, deviceIntegrity, mockLocationDetected, 
        latitude, longitude, studentId, timestamp, 
        scannedBeaconId, beaconRssi 
      } = attendance;

      // --- CRITICAL CHECK: SESSION ID ---
      if (!sessionId) {
        await snap.ref.update({ status: "rejected", reason: "no_sessionId" });
        await db.collection('logs').add({ attendanceId, reason: "no_sessionId", attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
        return null;
      }

      const sessionRef = db.collection('sessions').doc(sessionId);
      const sessionSnap = await sessionRef.get();
      if (!sessionSnap.exists) {
        await snap.ref.update({ status: "rejected", reason: "session_not_found" });
        await db.collection('logs').add({ attendanceId, reason: "session_not_found", attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
        return null;
      }

      const session = sessionSnap.data();
      const now = Date.now();
      let verificationDetails = {}; 

      // --- CHECK 1: QR MATCH ---
      if (scannedQrValue !== session.qrCodeValue) {
        await snap.ref.update({ status: "rejected", reason: "invalid_qr" });
        await db.collection('logs').add({ attendanceId, reason: "invalid_qr", sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
        return null;
      }

      // --- CHECK 2: TIMESTAMPS (Active Session) ---
      const createdAt = session.createdAt ? session.createdAt.toMillis() : 0;
      const expiresAt = session.expiresAt ? session.expiresAt.toMillis() : 0;

      if (!(createdAt <= now && now <= expiresAt)) {
        await snap.ref.update({ status: "rejected", reason: "session_inactive" });
        await db.collection('logs').add({ attendanceId, reason: "session_inactive", sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
        return null;
      }
      
      // --- CHECK 3: DEVICE INTEGRITY ---
      if (deviceIntegrity !== true) {
        await snap.ref.update({ status: "rejected", reason: "device_integrity_failed" });
        await db.collection('logs').add({ attendanceId, reason: "device_integrity_failed", sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
        return null;
      }

      // --- CHECK 4: MOCK LOCATION ---
      if (mockLocationDetected === true) {
        await snap.ref.update({ status: "rejected", reason: "mock_location_detected" });
        await db.collection('logs').add({ attendanceId, reason: "mock_location_detected", sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
        return null;
      }
      
      // --- CHECK 5: BLUETOOTH BEACON PROXIMITY ---
      const requiredBeaconId = session.requiredBeaconId;
      const minRequiredRssi = session.minRequiredRssi || -85; 

      if (requiredBeaconId) {
          if (!scannedBeaconId || typeof beaconRssi !== 'number') {
              await snap.ref.update({ status: "rejected", reason: "beacon_data_missing" });
              await db.collection('logs').add({ attendanceId, reason: "beacon_data_missing", sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
              return null;
          }

          if (scannedBeaconId !== requiredBeaconId) {
              await snap.ref.update({ status: "rejected", reason: "invalid_beacon_id" });
              await db.collection('logs').add({ attendanceId, reason: "invalid_beacon_id", sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
              return null;
          }

          if (beaconRssi < minRequiredRssi) {
              verificationDetails.submittedRssi = beaconRssi; 
              await snap.ref.update({ status: "rejected", reason: "beacon_too_far", submittedRssi: beaconRssi });
              await db.collection('logs').add({ attendanceId, reason: "beacon_too_far", submittedRssi: beaconRssi, sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
              return null;
          }
          verificationDetails.submittedRssi = beaconRssi;
      }

      // --- CHECK 6: GPS PROXIMITY (Haversine) ---
      if (!session.location || typeof session.location.latitude !== 'number' || typeof session.location.longitude !== 'number') {
        await snap.ref.update({ status: "rejected", reason: "session_location_missing" });
        await db.collection('logs').add({ attendanceId, reason: "session_location_missing", sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
        return null;
      }
      
      const distanceMeters = haversineDistanceMeters(session.location.latitude, session.location.longitude, latitude, longitude);
      const allowedRadius = session.allowedRadiusMeters || 50; 
      verificationDetails.distanceMeters = distanceMeters; 

      if (distanceMeters > allowedRadius) {
        await snap.ref.update({ status: "rejected", reason: "out_of_range", distanceMeters });
        await db.collection('logs').add({ attendanceId, reason: "out_of_range", distanceMeters, sessionId, attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
        return null;
      }

      // --- FINAL: ALL GOOD ---
      await snap.ref.update({ status: "present", verifiedAt: admin.firestore.FieldValue.serverTimestamp(), ...verificationDetails });
      await db.collection('logs').add({ attendanceId, reason: "verified_present", sessionId, attendance, ...verificationDetails, createdAt: admin.firestore.FieldValue.serverTimestamp() });

      return null;

    } catch (err) {
      console.error(`[FATAL ERROR] processing attendance ${attendanceId}:`, err);
      await snap.ref.update({ status: "rejected", reason: "internal_error" });
      await db.collection('logs').add({ attendanceId, reason: "internal_error", error: String(err), attendance, createdAt: admin.firestore.FieldValue.serverTimestamp() });
      return null;
    }
});


// ====================================================================
// 2. SCHEDULED FUNCTION (FIXED SYNTAX)
// ====================================================================

const ROTATION_INTERVAL_MINUTES = 5; 

// ❌ Old Syntax: exports.rotateActiveSessions = functions.pubsub.schedule(...)
// ✅ New Syntax: 
exports.rotateActiveSessions = onSchedule(`every ${ROTATION_INTERVAL_MINUTES} minutes`, async (event) => {
        try {
            // 1. Query for all sessions that are currently marked as 'active' 
            const activeSessionsSnapshot = await db.collection('sessions')
                .where('isActive', '==', true) 
                .get();

            if (activeSessionsSnapshot.empty) {
                console.log("[SESSION ROTATION] No active sessions found to rotate.");
                return null;
            }

            // Calculate the new expiry time once for the batch
            const expiryDate = new Date();
            expiryDate.setMinutes(expiryDate.getMinutes() + ROTATION_INTERVAL_MINUTES);
            const expiresAtTimestamp = admin.firestore.Timestamp.fromDate(expiryDate);
            
            const batch = db.batch(); // Use a batch write for efficiency

            for (const doc of activeSessionsSnapshot.docs) {
                const sessionRef = doc.ref;
                const sessionID = doc.id;
                
                // Generate a new, random 6-character QR code value for each active session
                const newQrValue = Math.random().toString(36).substring(2, 8).toUpperCase();
                
                // Update the session document using the batch
                batch.update(sessionRef, {
                    qrCodeValue: newQrValue,
                    // Set createdAt to current server time
                    createdAt: admin.firestore.FieldValue.serverTimestamp(), 
                    expiresAt: expiresAtTimestamp,
                    lastRotated: admin.firestore.FieldValue.serverTimestamp() 
                });

                console.log(`[SESSION ROTATION] Session ID ${sessionID} rotated. New QR: ${newQrValue}`);
            }
            
            // Commit all updates at once
            await batch.commit();

            return null;

        } catch (err) {
            console.error(`[SESSION ROTATION ERROR] Could not rotate sessions:`, err);
            return null;
        }
});