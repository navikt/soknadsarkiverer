package no.nav.soknad.arkivering.soknadsarkiverer

import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.utils.EmbeddedKafkaBrokerConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(EmbeddedKafkaBrokerConfig::class)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@EmbeddedKafka(topics = ["privat-soknadInnsendt-v1-default", "privat-soknadInnsendt-processingEventLog-v1-default", "privat-soknadInnsendt-messages-v1-default"], controlledShutdown = true)
class SoknadsarkivererApplicationTests {

	@Autowired
	private val embeddedKafka: EmbeddedKafkaBroker? = null

	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@AfterAll
	fun stopEmbeddedKafka() {
		embeddedKafka?.destroy()
	}

	@Test
	fun `Spring context loads`() {
	}
}
