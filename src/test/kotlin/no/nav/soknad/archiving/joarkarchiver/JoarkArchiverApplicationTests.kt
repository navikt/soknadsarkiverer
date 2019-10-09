package no.nav.soknad.archiving.joarkarchiver

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka

@SpringBootTest
@EmbeddedKafka(topics = ["archival"])
class JoarkArchiverApplicationTests {

	@Test
	fun `Spring context loads`() {
	}
}
