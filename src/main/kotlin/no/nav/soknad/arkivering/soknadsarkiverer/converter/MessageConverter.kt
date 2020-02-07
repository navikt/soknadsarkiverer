package no.nav.soknad.arkivering.soknadsarkiverer.converter

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.dto.FilElementDto
import no.nav.soknad.arkivering.dto.JoarkData
import org.springframework.stereotype.Service

@Service
class MessageConverter {
	fun createJoarkData(archivalData: ArchivalData, attachedFiles: List<FilElementDto>) =
		JoarkData(archivalData.id, archivalData.message, listOf("attachedFiles".toByteArray()))
}
