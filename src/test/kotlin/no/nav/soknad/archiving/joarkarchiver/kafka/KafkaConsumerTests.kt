package no.nav.soknad.archiving.joarkarchiver.kafka

import com.nhaarman.mockitokotlin2.*
import no.nav.soknad.archiving.dto.ArchivalData
import no.nav.soknad.archiving.joarkarchiver.service.JoarkArchiver
import org.junit.jupiter.api.Test

class KafkaConsumerTests {

	private val joarkArchiver = mock<JoarkArchiver> { }
	private val kafkaConsumer = KafkaConsumer(joarkArchiver)

	@Test
	fun `Call JoarkArchiver when receiving kafka message`() {
		val archivalData = ArchivalData("id", "message")

		kafkaConsumer.receiveFromKafka(archivalData)

		verify(joarkArchiver, times(1)).archive(eq(archivalData))
	}
}
