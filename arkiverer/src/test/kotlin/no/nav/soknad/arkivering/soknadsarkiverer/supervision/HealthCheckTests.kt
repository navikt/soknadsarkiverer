package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilestorageProperties
import no.nav.soknad.arkivering.soknadsarkiverer.utils.setupMockedNetworkServices
import no.nav.soknad.arkivering.soknadsarkiverer.utils.stopMockedNetworkServices
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
class HealthCheckTests  {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockBean
	private lateinit var collectorRegistry: CollectorRegistry

	@Autowired
	private  lateinit var filestorageProperties: FilestorageProperties

	@Autowired
	private lateinit var metrics: ArchivingMetrics
	@Value("\${joark.journal-post}")
	private lateinit var joarnalPostUrl: String

	private val applicationState = ApplicationState()
	private lateinit var healthCheck: HealthCheck

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, joarnalPostUrl, filestorageProperties.files)

		healthCheck = HealthCheck(applicationState, metrics)
	}

	@AfterEach
	fun cleanup() {
		stopMockedNetworkServices()
	}


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
