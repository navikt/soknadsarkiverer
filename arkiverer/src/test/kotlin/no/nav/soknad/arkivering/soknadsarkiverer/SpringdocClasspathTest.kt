package no.nav.soknad.arkivering.soknadsarkiverer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpringdocClasspathTest {

	@Test
	fun `Springdoc mapper provider has a single definition`() {
		assertSingleDefinition("org/springdoc/core/providers/ObjectMapperProvider.class")
	}

	@Test
	fun `Swagger schema annotation has a single definition`() {
		assertSingleDefinition("io/swagger/v3/oas/annotations/media/Schema.class")
	}

	private fun assertSingleDefinition(resource: String) {
		val definitions = javaClass.classLoader.getResources(resource).toList()
		assertEquals(1, definitions.size, "Expected one definition of $resource, found $definitions")
	}
}
