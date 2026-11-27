package no.nav.soknad.arkivering.soknadsarkiverer.schedule

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.*

class ReSendFailedApplicationsTests {

	private val taskListService = mockk<TaskListService>(relaxUnitFun = true)
	private val leaderSelectionUtility = mockk<LeaderSelectionUtility>()
	private val innsendingIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())

	@AfterEach
	fun cleanUp() {
		System.clearProperty(FAILED_APPLICATIONS)
	}

	@Test
	fun `The leader restarts every application in the Base64 encoded list`() {
		System.setProperty(FAILED_APPLICATIONS, encode(ApplicationList(innsendingIds)))
		every { leaderSelectionUtility.isLeader() } returns true

		ResendFailedApplications(leaderSelectionUtility, taskListService).start()

		innsendingIds.forEach { verify(exactly = 1) { taskListService.startPaNytt(it) } }
		verify(exactly = innsendingIds.size) { taskListService.startPaNytt(any()) }
	}

	@Test
	fun `A pod that is not the leader restarts nothing`() {
		System.setProperty(FAILED_APPLICATIONS, encode(ApplicationList(innsendingIds)))
		every { leaderSelectionUtility.isLeader() } returns false

		ResendFailedApplications(leaderSelectionUtility, taskListService).start()

		verify(exactly = 0) { taskListService.startPaNytt(any()) }
	}

	private fun encode(applications: ApplicationList): String =
		Base64.getEncoder().encodeToString(jacksonObjectMapper().writeValueAsBytes(applications))

	private companion object {
		const val FAILED_APPLICATIONS = "failedApplications"
	}
}
