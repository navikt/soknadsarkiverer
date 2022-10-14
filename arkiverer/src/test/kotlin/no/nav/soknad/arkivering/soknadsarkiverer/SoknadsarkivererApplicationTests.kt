package no.nav.soknad.arkivering.soknadsarkiverer

import com.ninjasquad.springmockk.MockkBean
import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
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
	private lateinit var collectorRegistry: CollectorRegistry

	@Test
	fun `Spring context loads`() {
	}
}
