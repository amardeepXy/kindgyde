package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KindGuideRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createCareCircleAndTask_validOwner_syncsInRealtime() = runBlocking {
        val seniorUid = signInTestUser("senior_${System.nanoTime()}@test.com")
        val repo = KindGuideRepository(firestore, auth)

        val circle = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createCareCircle(
                seniorName = "Grandpa Raj",
                pin = "1234",
                homeAddress = "101 Maple Way",
                homeLat = 37.7749,
                homeLng = -122.4194,
                emergencyContactName = "Aarav",
                emergencyContactPhone = "555-0100"
            ).getOrThrow()
        }

        val taskId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createTask(
                circleId = circle.id,
                memberUids = circle.memberUids,
                title = "Take morning walk",
                category = "Wellness",
                dueTimeLabel = "9:00 AM",
                createdBy = "ai"
            ).getOrThrow()
        }

        val tasks = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeTasks(circle.id, seniorUid).first { list ->
                list.any { it.id == taskId }
            }
        }
        assertTrue(tasks.any { it.id == taskId && it.title == "Take morning walk" })
    }

    @Test
    fun joinCareCircleByCode_validPin_allowsCaregiverAccess() = runBlocking {
        signInTestUser("senior_join_${System.nanoTime()}@test.com")
        val seniorRepo = KindGuideRepository(firestore, auth)
        val circle = withTimeout(DEFAULT_TIMEOUT_MS) {
            seniorRepo.createCareCircle(
                seniorName = "Grandma Meera",
                pin = "2468",
                homeAddress = "42 Oak St",
                homeLat = 37.77,
                homeLng = -122.41,
                emergencyContactName = "Priya",
                emergencyContactPhone = "555-0199"
            ).getOrThrow()
        }

        val caregiverUid = signInTestUser("caregiver_${System.nanoTime()}@test.com")
        val caregiverRepo = KindGuideRepository(firestore, auth)
        val joinedCircleId = withTimeout(DEFAULT_TIMEOUT_MS) {
            caregiverRepo.joinCareCircleByCode(circle.inviteCode, "2468").getOrThrow()
        }
        assertEquals(circle.id, joinedCircleId)

        val circles = withTimeout(FLOW_TIMEOUT_MS) {
            caregiverRepo.observeCareCircles(caregiverUid).first { it.any { c -> c.id == circle.id } }
        }
        val updatedCircle = circles.first { it.id == circle.id }
        assertTrue(caregiverUid in updatedCircle.memberUids)

        val eventId = withTimeout(DEFAULT_TIMEOUT_MS) {
            caregiverRepo.createEvent(
                circleId = updatedCircle.id,
                memberUids = updatedCircle.memberUids,
                title = "Cardiology Checkup",
                dateLabel = "Nov 18",
                timeLabel = "10:30 AM",
                location = "City Clinic",
                notes = "Bring medication list",
                createdBy = "caregiver"
            ).getOrThrow()
        }
        assertTrue(eventId.isNotBlank())
    }

    @Test
    fun observeCareCircles_strangerAndUnauthenticated_isolatedProperly() = runBlocking {
        signInTestUser("owner_iso_${System.nanoTime()}@test.com")
        val ownerRepo = KindGuideRepository(firestore, auth)
        val circle = withTimeout(DEFAULT_TIMEOUT_MS) {
            ownerRepo.createCareCircle(
                seniorName = "Grandpa Private",
                pin = "9999",
                homeAddress = "Private Lane",
                homeLat = 37.78,
                homeLng = -122.42,
                emergencyContactName = "Son",
                emergencyContactPhone = "555-0111"
            ).getOrThrow()
        }

        // Cross-user isolation: stranger receives empty list and cannot write into owner's circle
        val strangerUid = signInTestUser("stranger_${System.nanoTime()}@test.com")
        val strangerRepo = KindGuideRepository(firestore, auth)
        val strangerCircles = withTimeout(FLOW_TIMEOUT_MS) {
            strangerRepo.observeCareCircles(strangerUid).first()
        }
        assertTrue(strangerCircles.none { it.id == circle.id })

        val writeFailure = runCatching {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                strangerRepo.createTask(
                    circleId = circle.id,
                    memberUids = listOf(strangerUid),
                    title = "Unauthorized task"
                ).getOrThrow()
            }
        }.exceptionOrNull()
        val fsError = writeFailure?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError?.code)

        // Unauthenticated query rejected
        auth.signOut()
        val unauthFailure = runCatching {
            withTimeout(FLOW_TIMEOUT_MS) {
                strangerRepo.observeCareCircles(strangerUid).first()
            }
        }.exceptionOrNull()
        val unauthFsError = unauthFailure?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, unauthFsError?.code)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
