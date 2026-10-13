package no.nav.soknad.arkivering.soknadsarkiverer

import com.ninjasquad.springmockk.MockkBean
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.ContainerizedKafka
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
class SoknadsarkivererApplicationTests : ContainerizedKafka() {

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var metrics: ArchivingMetrics

	@Test
	fun `Spring context loads`() {
	}
}
