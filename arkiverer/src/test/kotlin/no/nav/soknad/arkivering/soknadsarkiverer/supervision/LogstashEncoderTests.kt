package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.logstash.logback.encoder.LogstashEncoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MarkerFactory
import java.time.Instant
import java.time.OffsetDateTime

class LogstashEncoderTests {

	@Test
	fun `team JSON logs preserve timestamps, correlation IDs and custom fields`() {
		val context = LoggerContext()
		context.putProperty("contextOnly", "not included")
		val encoder = LogstashEncoder().apply {
			this.context = context
			customFields = """{"nais_container_name":"soknadsarkiverer"}"""
			isIncludeContext = false
		}
		try {
			encoder.start()
			assertTrue(encoder.isStarted)
			val timestamp = Instant.parse("2026-09-01T00:00:00Z")
			val event = LoggingEvent(
				javaClass.name, context.getLogger("team-logs-logger"), Level.INFO,
				"Archive completed", null, null
			).apply {
				timeStamp = timestamp.toEpochMilli()
				threadName = "logging-test"
				mdcPropertyMap = mapOf("correlationId" to "test-correlation")
				addMarker(MarkerFactory.getMarker("TEAM_LOGS"))
			}

			val json = jacksonObjectMapper().readTree(encoder.encode(event))
			assertEquals("Archive completed", json["message"].asText())
			assertEquals("team-logs-logger", json["logger_name"].asText())
			assertEquals("INFO", json["level"].asText())
			assertEquals(timestamp, OffsetDateTime.parse(json["@timestamp"].asText()).toInstant())
			assertEquals("test-correlation", json["correlationId"].asText())
			assertEquals("soknadsarkiverer", json["nais_container_name"].asText())
			assertTrue(json["tags"].any { it.asText() == "TEAM_LOGS" })
			assertNull(json["contextOnly"])
		} finally {
			encoder.stop()
			context.stop()
		}
	}
}
