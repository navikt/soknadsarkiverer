package no.nav.soknad.arkivering.soknadsarkiverer.converter

import no.nav.soknad.arkivering.dto.FilElementDto
import no.nav.soknad.arkivering.dto.JoarkData
import no.nav.soknad.arkivering.dto.SoknadMottattDto
import org.springframework.stereotype.Service

@Service
class MessageConverter {
	fun createJoarkData(o: SoknadMottattDto, attachedFiles: List<FilElementDto>) =
		JoarkData(o.eksternReferanseId, o.personId, o.idType, o.tema, o.innsendtDato, o.mottatteDokumenter, attachedFiles)
}
