package no.nav.soknad.arkivering.soknadsarkiverer.schedule

import io.mockk.every
import io.mockk.mockk
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.*

class ReSendFailedApplicationsTests {

	private val taskListService = mockk<TaskListService>(relaxUnitFun = true)
	private val leaderSelectionUtility = mockk<LeaderSelectionUtility>()
	private val sourceFile = "failedApplications"

	@Autowired
	private val objectMapper: ObjectMapper = jacksonObjectMapper()

	fun lagInput(): ApplicationList {
		val innsendingIds = listOf<String>("4aee359a-a6b7-472d-bc71-70df92a5642d","53a2b9d6-ae8b-4274-bfc2-d1d20e09278f")
		return ApplicationList(innsendingIds)
	}

	@AfterEach
	fun cleanUp() {
		File(sourceFile).delete()
	}

	@Test
	fun testLesOgKonverterInput() {
		val resendFailedApplications = ResendFailedApplications(leaderSelectionUtility, taskListService)
		val failedApplications = lagInput()
		val filePath = ""

		val jsonString = objectMapper.writeValueAsString(failedApplications)
		val encodedJsonString: String = Base64.getEncoder().encodeToString(jsonString.toByteArray())

		writeBytesToFile(jsonString.toByteArray(Charsets.UTF_8), filePath+sourceFile)

		System.setProperty("failedApplications", encodedJsonString)

		every { leaderSelectionUtility.isLeader() } returns true
		val innsendingIds = mutableListOf<String>()
		every { taskListService.startPaNytt(capture(innsendingIds)) } returns Unit

		resendFailedApplications.start()

		assertTrue(innsendingIds.isNotEmpty())

	}

	fun writeBytesToFile(data: ByteArray, filePath: String) {
		File(filePath).writeBytes(data)
	}

	fun readeBytesFromFile(filePath: String): ByteArray {
		return File(filePath).readBytes()
	}


	@Test
	fun lagBase64Encoded() {
		/*
		Denne testen benyttes for å generere en Base64encoded string som kan legges inn som value i google secret, f.eks.
		FAILED_APPLICATIONS=eyJpbm5zZW5kaW5nSWRzIjpbIjBhMGIwYmUxLTcyYzktNGRkMC05MDdkLTZkZjRjNzMyZjFlMiJdfQo=
		 */
		val jsonByteArray = readeBytesFromFile("innsendingsIds.json")

		val encodedString: String = Base64.getEncoder().encodeToString(jsonByteArray)

		val input = objectMapper.readValue(String(Base64.getDecoder().decode(encodedString)), object: TypeReference<ApplicationList>(){})

		Assertions.assertTrue(input.innsendingIds.size > 0 )

	}

	/*
		@Test
		fun findUsersWithMissingZero() {
			/*
				Denne testen skal normalt utkommentert. Brukt for å fikse på fødselsnummeret som var blitt feil.
				Kan kanskje brukes som et utgangspunkt for annen type oppbygging av brukerliste når det er behov for sending av varsel.
			 */
			val jsonByteArray = readeBytesFromFile("userNotificationMessage.json")
			val jsonString = jsonByteArray.decodeToString()

			val gson = Gson()

			val userNotificationMessageDto = gson.fromJson(jsonString, UserNotificationMessageDto::class.java)

			val userList: List<UserDto> = userNotificationMessageDto.userList.filter{it.userId.length==10}.map { UserDto(it.innsendingRef,"0"+it.userId, it.schema, it.language) }

			val fixedNotificationMessages = UserNotificationMessageDto(userNotificationMessageDto.userMessage, userNotificationMessageDto.userMessage_en, userNotificationMessageDto.messageLinkBase, userList)

			val fixedJsonString = gson.toJson(fixedNotificationMessages)

			writeBytesToFile(fixedJsonString.toByteArray(Charsets.UTF_8), "userNotificationMessage-fixed.json")

		}
	*/


}
