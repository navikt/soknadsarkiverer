package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.mockk.every
import io.mockk.mockk
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

class HealthCheckTests  {

	private val metrics = mockk<ArchivingMetrics>().also { every { it.setUpOrDown(any()) } returns Unit }
	private val appConfiguration = AppConfiguration()
	private val applicationState = appConfiguration.state
	private val healthCheck = HealthCheck(appConfiguration, metrics)


	@Test
	fun `isAlive returns Ok when application is well`() {
		applicationState.alive = true

		val response = healthCheck.isAlive()

		assertEquals(ResponseEntity<String>(HttpStatus.OK), response)
	}

	@Test
	fun `isAlive returns Status 500 when application is unwell`() {
		applicationState.alive = false

		val response = healthCheck.isAlive()

		assertEquals(ResponseEntity("Application is not alive", HttpStatus.INTERNAL_SERVER_ERROR), response)
	}


	@Test
	fun `isReady returns Ok when application is well`() {
		applicationState.ready = true

		val response = healthCheck.isReady()

		assertEquals(ResponseEntity<String>(HttpStatus.OK), response)
	}

	@Test
	fun `isReady returns Status 500 when application is unwell`() {
		applicationState.ready = false

		val response = healthCheck.isReady()

		assertEquals(ResponseEntity("Application is not ready", HttpStatus.INTERNAL_SERVER_ERROR), response)
	}

	@Test
	fun `isReady returns Status 500 when application is stopping`() {
		healthCheck.stop()

		val response = healthCheck.isReady()

		assertEquals(ResponseEntity("Application is not ready", HttpStatus.INTERNAL_SERVER_ERROR), response)
	}
}
