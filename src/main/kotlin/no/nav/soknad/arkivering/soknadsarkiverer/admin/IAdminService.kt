package no.nav.soknad.arkivering.soknadsarkiverer.admin

interface IAdminService {

	fun rerun(key: String)

	fun pingJoark(): String

	fun pingFilestorage(): String

	fun filesExist(key: String): List<FilestorageExistenceResponse>

	fun getUnfinishedEvents(before: Boolean?, timestamp: Long?): List<KafkaEvent<String>>

	fun getfailedEvents(before: Boolean?, timestamp: Long?): List<KafkaEvent<String>>

	fun getAllRequestedEvents(before: Boolean?, timestamp: Long?): List<KafkaEvent<String>>

	fun getAllRequestedEventsFilteredByKey(key: String, before: Boolean?, timestamp: Long?): List<KafkaEvent<String>>

	fun getAllRequestedEventsFilteredByRegx(searchPhrase: String, before: Boolean?, timestamp: Long? ): List<KafkaEvent<String>>
}
