package no.nav.soknad.arkivering.soknadsarkiverer.converter

import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.dto.JoarkData
import no.nav.soknad.soknadarkivering.avroschemas.Soknadarkivschema
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class MessageConverter {
	fun createJoarkData(o: Soknadarkivschema, attachedFiles: List<FilElementDto>) =
		JoarkData(o.getBehandlingsid(), o.getFodselsnummer(), o.getHenvendelsetype(), o.getArkivtema(),
			LocalDateTime.ofInstant(Instant.ofEpochMilli(o.getHenvendelseInnsendtDato()), ZoneOffset.UTC),
			o.getMottatteDokumenter(), attachedFiles)
}
