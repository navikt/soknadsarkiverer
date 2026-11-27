package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.*
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import org.apache.kafka.common.header.internals.RecordHeaders
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.eq
import java.time.format.DateTimeFormatter
import java.util.*

class ApplicationTests : ArchivingFlowTest() {

	@ParameterizedTest
	@ValueSource(strings = ["NAV_NO", "NAV_NO_UINNLOGGET"])
	fun `Happy case - application is archived and reported OK`(kanal: String) {
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val mainDocumentTitle = "Test dokument"
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel(mainDocumentTitle)
			.withKanal(kanal)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, mainDocumentTitle, fileIds))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId

		mockFilestorageIsWorking(fileIds.map { it to filestorageContent })
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(soknadsarkivschema)
		kafkaListener.awaitFinished(key)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "Exception" hasCount 0))
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: OK" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 1,
			)
		)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)
	}

	@Test
	fun `Happy case - Received message archieved, but feedback to innsender fails state set to finished`() {
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val mainDocumentTitle = "Test dokument"
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel(mainDocumentTitle)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, mainDocumentTitle, fileIds))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId

		mockFilestorageIsWorking(fileIds.map { it to filestorageContent })
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)
		doThrow(RuntimeException("Failed to send arkiveringstilbakemelding")).whenever(kafkaPublisher)
			.putArkiveringstilbakemeldingOnTopic(any(), any(), any())
		doNothing().whenever(kafkaPublisher)
			.putMessageOnTopic(eq(key), eq("**Archiving: OK."), any())

		putDataOnKafkaTopic(soknadsarkivschema)
		kafkaListener.awaitFinished(key)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount maxNumberOfAttempts, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 1,
			)
		)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)
	}

	@Test
	fun `Happy case - File missing, new event event on Kafka for key will cause rest calls to Joark`() {
		val fileId = UUID.randomUUID().toString()
		val mainDocumentTitle = "Test dokument"
		mockFilestorageIsWorking(fileId)

		mockJoarkIsWorking()
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel(mainDocumentTitle)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, mainDocumentTitle, listOf("non-existing-file-id")))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(soknadsarkivschema)
		kafkaListener.awaitFailureHandled(key, taskListService)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount maxNumberOfAttempts, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 1
			)
		)

		val updatedSchema = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel(mainDocumentTitle)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, mainDocumentTitle, listOf(fileId)))
			)
			.build()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)
		putDataOnKafkaTopic(updatedSchema)
		kafkaListener.awaitFinished(key)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 2, STARTED hasCount maxNumberOfAttempts+1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 1
			)
		)

		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(updatedSchema, request)
	}

	@Test
	fun `Reject updated application on kafka when already archived`() {
		val fileId = UUID.randomUUID().toString()
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel("Test dokument")
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, "Test dokument", listOf(fileId)))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId

		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(soknadsarkivschema)
		kafkaListener.awaitFinished(key)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)

		val updatedFileId = UUID.randomUUID().toString()
		val dokument = soknadsarkivschema.dokumenter.first()
		val variant = dokument.varianter.first().copy(uuid=updatedFileId)
		val updatedDokument = dokument.copy(varianter = listOf(variant))
		val updatedSchema = soknadsarkivschema.copy(dokumenter = listOf(updatedDokument))

		mockFilestorageIsWorking(updatedFileId)
		mockJoarkIsWorking()
		mockSafRequest_found(innsendingsId = key)

		putDataOnKafkaTopic(updatedSchema)
		awaitLaterApplicationArchived(updatedSchema.kanal)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 2, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)

	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsDown()
		mockSafRequest_notFound(innsendingsId = key)
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		// When
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFailureHandled(key, taskListService)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMockedPostRequests(maxNumberOfAttempts, safUrl)
		verifyMessageStartsWith(
			key,
			mapOf("**Archiving: FAILED" hasCount 1, "ok" hasCount 0, "Exception" hasCount maxNumberOfAttempts)
		)
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: FAILED" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount maxNumberOfAttempts,
				"send files to archive" hasCount 0,
			)
		)
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val keyForPoisonPill = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		putDataOnTopic(keyForPoisonPill, "this is not deserializable", RecordHeaders(), topic= kafkaConfig.topics.loggedinSubmissionTopic,
			kafkaProducer = loggedinTopicProducer)
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFinished(key)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyProcessingEvents(
			keyForPoisonPill, mapOf(
				RECEIVED hasCount 0, STARTED hasCount 0, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: OK" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 1,
			)
		)
	}

	fun numbersOfJoarkFailuresBeforeSuccess() = listOf(1, tasklistProperties.secondsBetweenRetries.size - 1)

	@ParameterizedTest
	@MethodSource("numbersOfJoarkFailuresBeforeSuccess")
	fun `Joark fails and a later attempt succeeds`(numberOfFailures: Int) {

		// Given
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkRespondsAfterAttempts(numberOfFailures)
		mockSafRequest_notFound(innsendingsId = key)

		// When
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFinished(key)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount numberOfFailures + 1,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(numberOfFailures + 1, safUrl)
		verifyMockedPostRequests(numberOfFailures + 1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("Exception" hasCount numberOfFailures))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount numberOfFailures + 1,
				"send files to archive" hasCount 1,
			)
		)

		verifyArchivingMetrics(getFilestorageSuccessesBefore + numberOfFailures + 1, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + numberOfFailures, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 1, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore + 0, { metrics.getTasks() }, "Should have created and finished task")
		verifyArchivingMetrics(
			tasksGivenUpOnBefore + 0,
			{ metrics.getTasksGivenUpOn() },
			"Should not have given up on any task"
		)
	}

	@Test
	fun `First attempt to Joark fails, later found in archive`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val attemptsToFail = 1
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkRespondsAfterAttempts(attemptsToFail)
		mockSafRequest_foundAfterAttempt(innsendingsId = key, attempts = attemptsToFail)

		// When
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFinished(key)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount attemptsToFail + 1,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(attemptsToFail + 1, safUrl)
		verifyMockedPostRequests(attemptsToFail, journalPostUrl)
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount attemptsToFail,
				"send files to archive" hasCount 0,
			)
		)
		verifyArchivingMetrics(
			tasksGivenUpOnBefore + 0,
			{ metrics.getTasksGivenUpOn() },
			"Should not have given up on any task"
		)
	}

	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsWorkingButGivesInvalidResponse()
		mockSafRequest_notFound(innsendingsId = key)

		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFailureHandled(key, taskListService)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMockedPostRequests(maxNumberOfAttempts, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("Exception" hasCount maxNumberOfAttempts))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount maxNumberOfAttempts,
				"send files to archive" hasCount 0,
			)
		)
	}

	@Test
	fun `Application already archived will cause finishing archiving`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockAlreadyArchivedResponse(1)
		mockSafRequest_notFound(innsendingsId = key)

		// When
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFinished(key)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "Exception" hasCount 1))
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: OK" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 0,
			)
		)

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 1, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Application found after calling saf will cause finishing archiving`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockAlreadyArchivedResponse(1)
		mockSafRequest_found(innsendingsId = key)

		// When
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFinished(key)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 0,
				"send files to archive" hasCount 0,
			)
		)

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Application is archived when the SAF check fails`() {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsWorking()
		mockSafRequest_error(innsendingsId = key)

		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFinished(key)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
	}
	@Test
	fun `Failing to get files from Filestorage will cause retries`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsDown()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()

		// When
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFailureHandled(key, taskListService)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMessageStartsWith(key, mapOf("Exception" hasCount maxNumberOfAttempts))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 0,
				"send files to archive" hasCount 0,
			)
		)

		verifyArchivingMetrics(getFilestorageErrorsBefore + maxNumberOfAttempts, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		//verifyArchivingMetrics(tasksBefore + 1, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Not all files fetched from Filestorage will cause failure`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockRequestedFileIsNotFound()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		// When
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFailureHandled(key, taskListService)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMessageStartsWith(key, mapOf("ok" hasCount 0, "Exception" hasCount maxNumberOfAttempts))

		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 0,
				"send files to archive" hasCount 0,
			)
		)

		verifyArchivingMetrics(getFilestorageErrorsBefore + maxNumberOfAttempts, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore + 1, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Failing to archive and failing to send feedback to innsender`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsDown()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)
		doThrow(RuntimeException("Failed to send arkiveringstilbakemelding")).whenever(kafkaPublisher).putArkiveringstilbakemeldingOnTopic(any(), any(), any())
		doNothing().whenever(kafkaPublisher).putMessageOnTopic(
			eq(key), eq("**Archiving: FAILED."), any())

		// When
		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFailureHandled(key, taskListService)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount  maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				// Each new attempt to send the failure feedback publishes FAILURE again
				FAILURE hasCount maxNumberOfAttempts
			)
		)
		verify(archiverService, times(maxNumberOfAttempts)).createArkiveringstilbakemelding(eq(key), any())
		verifyMessageStartsWith(key, mapOf("**Feedback to innsending-api: FAILED" hasCount 1))

	}

	/**
	 * Archives another application on the same submission topic and waits for it to finish.
	 * Every test topic has one partition, and one stream thread handles the submissions and the
	 * processing events in order. When the later application is FINISHED, the application has
	 * handled the records published before it. If it had accepted one of them, it would already
	 * have published STARTED for that key.
	 */
	private fun awaitLaterApplicationArchived(kanal: String) {
		val fileId = UUID.randomUUID().toString()
		val laterApplication = InnsendingTopicMsgBuilder()
			.withKanal(kanal)
			.withTestDokumenter(mutableListOf(TestDokument("NAV 11-12.12", true, "Test dokument", listOf(fileId))))
			.build()
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = laterApplication.innsendingsId)
		putDataOnKafkaTopic(laterApplication)
		kafkaListener.awaitFinished(laterApplication.innsendingsId)
	}

	private fun verifyRequestDataToJoark(soknadsarkivschema: InnsendingTopicMsg, requestData: OpprettJournalpostRequest) {
		val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

		val (mainDocumentTitle, brevkode) = getTitleAndBrevkode(soknadsarkivschema)

		val expected = OpprettJournalpostRequest(
			AvsenderMottaker(soknadsarkivschema.avsenderDto.id, idType = soknadsarkivschema.avsenderDto.idType?.name, navn = soknadsarkivschema.avsenderDto.navn),
			bruker = if (soknadsarkivschema.brukerDto != null) Bruker(id=soknadsarkivschema.brukerDto?.id!!, idType = soknadsarkivschema.brukerDto?.idType?.name!!) else null,
			datoMottatt = soknadsarkivschema.innsendtDato.format(formatter),
			dokumenter = soknadsarkivschema.dokumenter.mapIndexed{ index, doc ->
				Dokument(
					tittel=if (index == 0) mainDocumentTitle else doc.tittel, brevkode=if (index == 0) brevkode else doc.skjemanummer, dokumentKategori="SOK",
					dokumentvarianter = doc.varianter.map{variant ->
						DokumentVariant(filnavn=variant.filnavn, filtype=variant.filtype, fysiskDokument = filestorageContent.toByteArray(), variantformat = variant.variantFormat!!)
					}
				)},
			eksternReferanseId = soknadsarkivschema.innsendingsId,
			journalpostType = "INNGAAENDE",
			kanal = soknadsarkivschema.kanal,
			tema = soknadsarkivschema.arkivtema,
			tittel = mainDocumentTitle
		)
		assertEquals(expected, requestData)
	}

	private fun getTitleAndBrevkode(soknadsarkivschema: InnsendingTopicMsg): Pair<String, String> {
		if (soknadsarkivschema.ettersendelseTilId != null) {
			return Pair("Ettersendelse til " + soknadsarkivschema.tittel.replaceFirst(soknadsarkivschema.tittel[0], soknadsarkivschema.tittel[0].lowercaseChar()),
				soknadsarkivschema.dokumenter.firstOrNull()?.skjemanummer?.replace("NAV ", "NAVe ")?: "")
		} else {
			return Pair(soknadsarkivschema.tittel,	soknadsarkivschema.dokumenter.firstOrNull()?.skjemanummer ?: "")
		}
	}

}
