package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.dto.ArchivalData
import org.springframework.stereotype.Service

@Service
class FileStorageRetrievingService {

	fun getFilesFromFileStorage(archivalData: ArchivalData): List<ByteArray> {

		// TODO: fetch from file storage. Until that is in place: Just return something in the mean time
		return listOf(archivalData.message.toByteArray())
	}
}
