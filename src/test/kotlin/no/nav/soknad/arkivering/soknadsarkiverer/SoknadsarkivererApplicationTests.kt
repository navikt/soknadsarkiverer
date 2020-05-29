package no.nav.soknad.arkivering.soknadsarkiverer

import no.nav.soknad.arkivering.soknadsarkiverer.utils.EmbeddedKafkaBrokerConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
@Import(EmbeddedKafkaBrokerConfig::class)
@EmbeddedKafka(topics = ["privat-soknadInnsendt-v1-default", "privat-soknadInnsendt-processingEventLog-v1-default", "privat-soknadInnsendt-messages-v1-default"])
class SoknadsarkivererApplicationTests {

	@Test
	fun `Spring context loads`() {
	}
}
