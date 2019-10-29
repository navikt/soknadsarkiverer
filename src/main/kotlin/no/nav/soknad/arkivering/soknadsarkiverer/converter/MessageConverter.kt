package no.nav.soknad.arkivering.soknadsarkiverer.converter

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.dto.JoarkData

class MessageConverter {
	fun createJoarkData(archivalData: ArchivalData, attachedFiles: List<ByteArray>) = JoarkData(archivalData.id, archivalData.message, attachedFiles)
}
