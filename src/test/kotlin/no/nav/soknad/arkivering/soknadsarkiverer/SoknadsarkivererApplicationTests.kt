package no.nav.soknad.arkivering.soknadsarkiverer

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
@EmbeddedKafka(topics = ["\${application.kafka-topic}"])
class SoknadsarkivererApplicationTests {

	@Test
	fun `Spring context loads`() {
	}
}
