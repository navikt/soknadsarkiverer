package no.nav.soknad.arkivering.soknadsarkiverer

import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.security.token.support.spring.api.EnableJwtTokenValidation
import no.nav.soknad.arkivering.soknadsarkiverer.utils.EmbeddedKafkaBrokerConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Import(EmbeddedKafkaBrokerConfig::class)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@EmbeddedKafka(topics = ["privat-soknadInnsendt-v1-default", "privat-soknadInnsendt-processingEventLog-v1-default", "privat-soknadInnsendt-messages-v1-default"])
class SoknadsarkivererApplicationTests {

	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Test
	fun `Spring context loads`() {
	}
}
