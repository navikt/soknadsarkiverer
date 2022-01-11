package no.nav.soknad.arkivering.soknadsarkiverer.admin

interface IAdminService {

	fun rerun(key: String)

	fun pingJoark(): String

	fun pingFilestorage(): String

	fun filesExist(key: String): List<FilestorageExistenceResponse>

	fun getUnfinishedEvents(timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>>

	fun getFailedEvents(timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>>

	fun getAllEvents(timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>>

	fun getEventsByKey(key: String, timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>>

	fun getEventsByRegex(searchPhrase: String, timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>>
}

enum class TimeSelector { BEFORE, AFTER, ANY }
