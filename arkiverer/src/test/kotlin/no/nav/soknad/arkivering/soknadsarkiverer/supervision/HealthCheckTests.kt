package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.reactive.function.client.WebClientResponseException

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
class HealthCheckTests {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockBean
	private lateinit var collectorRegistry: CollectorRegistry

	@Autowired
	private lateinit var filestorage: FileserviceInterface

	@Autowired
	private lateinit var journalpostClient: JournalpostClientInterface

	@Autowired
	private lateinit var metrics: ArchivingMetrics

	private val appConfiguration = AppConfiguration()
	private lateinit var healthCheck: HealthCheck

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		mockFilestoragePingIsWorking()
		mockFilestorageIsReadyIsWorking()
		mockJoarkIsAliveIsWorking()

		healthCheck = HealthCheck(appConfiguration, filestorage, journalpostClient, metrics)
	}

	@AfterEach
	fun cleanup() {
		stopMockedNetworkServices()
	}


	@Test
	fun `isAlive returns Ok when application is well`() {
		appConfiguration.state.alive = true

		assertEquals("Ok", healthCheck.isAlive())
	}

	@Test
	fun `isAlive returns Status 500 when application is unwell`() {
		appConfiguration.state.alive = false

		val e = assertThrows<HttpServerErrorException> { healthCheck.isAlive() }
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.statusCode)
	}


	@Test
	fun `isReady returns Ok when application and dependencies are well`() {
		appConfiguration.state.ready = true

		assertEquals("Ready for actions", healthCheck.isReady())
	}

	@Test
	fun `isReady returns Status 500 when application is unwell`() {
		appConfiguration.state.ready = false

		val e = assertThrows<HttpServerErrorException> { healthCheck.isReady() }
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.statusCode)
	}

	@Test
	fun `isReady returns Status 500 when application is stopping`() {
		healthCheck.stop()

		val e = assertThrows<HttpServerErrorException> { healthCheck.isReady() }
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.statusCode)
	}

	@Test
	fun `isReady returns Status 500 when Filestorage is unwell`() {
		mockFilestorageIsReadyIsNotWorking()

		val e = assertThrows<WebClientResponseException.InternalServerError> { healthCheck.isReady() }
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.statusCode)
	}

	@Test
	fun `isReady returns Status 500 when Joark is unwell`() {
		mockJoarkIsAliveIsNotWorking()

		val e = assertThrows<WebClientResponseException.InternalServerError> { healthCheck.isReady() }
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.statusCode)
	}


	@Test
	fun `ping returns Pong when dependencies are well`() {
		assertEquals("pong", healthCheck.ping())
	}

	@Test
	fun `ping returns Status 500 when Filestorage is unwell`() {
		mockFilestoragePingIsNotWorking()

		val e = assertThrows<WebClientResponseException.InternalServerError> { healthCheck.ping() }
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.statusCode)
	}

	@Test
	fun `ping returns Status 500 when Joark is unwell`() {
		mockJoarkIsAliveIsNotWorking()

		val e = assertThrows<WebClientResponseException.InternalServerError> { healthCheck.isReady() }
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.statusCode)
	}
}
