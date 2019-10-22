package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class KafkaConsumer(private val joarkArchiver: JoarkArchiver) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@KafkaListener(topics = ["privat-soknadInnsendt-sendsoknad-v1-q0"])
	fun receiveFromKafka(archivalData: ArchivalData) {
		logger.info("Received message: '$archivalData'")
		joarkArchiver.archive(archivalData)
	}
}
