package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
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
