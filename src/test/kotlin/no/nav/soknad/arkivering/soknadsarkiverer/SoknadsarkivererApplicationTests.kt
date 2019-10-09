package no.nav.soknad.arkivering.soknadsarkiverer

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka

@SpringBootTest
@EmbeddedKafka(topics = ["archival"])
class SoknadsarkivererApplicationTests {

	@Test
	fun `Spring context loads`() {
	}
}
