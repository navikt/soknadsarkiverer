package no.nav.soknad.arkivering.soknadsarkiverer

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka

@SpringBootTest
@EmbeddedKafka(topics = ["privat-soknadInnsendt-sendsoknad-v1-q0"])
class SoknadsarkivererApplicationTests {

	@Test
	fun `Spring context loads`() {
	}
}
