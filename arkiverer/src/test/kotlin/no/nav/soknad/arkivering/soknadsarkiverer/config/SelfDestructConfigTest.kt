package no.nav.soknad.arkivering.soknadsarkiverer.config

import io.mockk.mockk
import io.mockk.verify
import no.nav.soknad.arkivering.soknadsarkiverer.schedule.LeaderSelectionUtility
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The self-destruct task is scheduled hours ahead. In profiles where it is scheduled, the task scheduler waits
 * 30 seconds for it when the application context closes. It must therefore only be scheduled in the Nais profiles.
 */
class SelfDestructConfigTest {

	private val scheduler = mockk<Scheduler>(relaxed = true)

	private fun contextRunner(profile: String) = ApplicationContextRunner()
		.withInitializer { it.environment.setActiveProfiles(profile) }
		.withBean(Scheduler::class.java, { scheduler })
		.withBean(ApplicationState::class.java, { ApplicationState() })
		.withBean(LeaderSelectionUtility::class.java, { mockk<LeaderSelectionUtility>() })
		.withUserConfiguration(SelfDestructConfig::class.java)

	@Test
	fun `self-destruct is not scheduled in the test profile`() {
		contextRunner("test").run { context ->
			assertTrue(context.getBeansOfType(SelfDestructConfig::class.java).isEmpty())
		}

		verify(exactly = 0) { scheduler.scheduleSingleTask(any(), any()) }
	}

	@Test
	fun `self-destruct is scheduled in the prod profile`() {
		contextRunner("prod").run { context ->
			assertTrue(context.getBeansOfType(SelfDestructConfig::class.java).isNotEmpty())
		}

		verify(exactly = 1) { scheduler.scheduleSingleTask(any(), any()) }
	}

	@Test
	fun `self-destruct is scheduled in the dev profile`() {
		contextRunner("dev").run { context ->
			assertTrue(context.getBeansOfType(SelfDestructConfig::class.java).isNotEmpty())
		}

		verify(exactly = 1) { scheduler.scheduleSingleTask(any(), any()) }
	}
}
