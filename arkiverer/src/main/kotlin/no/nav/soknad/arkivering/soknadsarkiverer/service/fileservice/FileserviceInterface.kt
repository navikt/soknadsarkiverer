package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsfillager.model.FileData

interface FileserviceInterface {
	fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): FetchFileResponse

	fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema)

	fun ping(): String

	/**
	List of the applicationnumbers handled by sendsoknad. Soknadsfillager is used by sendsoknad to store the files that
	shall be archived related to these appliations.Soknadsarkiverer uses this into to filter for which applications
	it will try to fetch files from soknadsfillager. Note that soknadsarkiverer will always try to fetch files for an
	application	from innsending-api.
	*/
	val relevantApplicationNumbers: List<String>
		get() = listOf("NAV 11-12.10", "NAV 11-12.11", "NAV 11-12.12", "NAV 11-12.13", "NAV 76-13.45")

}
