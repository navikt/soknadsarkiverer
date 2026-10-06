package no.nav.soknad.arkivering.soknadsarkiverer

import com.ninjasquad.springmockk.MockkBean
import io.mockk.verify
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaStreamsSetup
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SoknadsarkivererApplicationTests {

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var metrics: ArchivingMetrics

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var kafkaStreamsSetup: KafkaStreamsSetup

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var kafkaPublisher: KafkaPublisher

	@Test
	fun `Spring context loads`() {
		verify(exactly = 1) { kafkaStreamsSetup.setupKafkaStreams(any()) }
	}
}
