package no.nav.soknad.arkivering.soknadsarkiverer

import com.ninjasquad.springmockk.MockkBean
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
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
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var metrics: ArchivingMetrics

	@Test
	fun `Spring context loads`() {
	}
}
