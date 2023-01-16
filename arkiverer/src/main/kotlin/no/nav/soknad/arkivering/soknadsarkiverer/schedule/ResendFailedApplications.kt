package no.nav.soknad.arkivering.soknadsarkiverer.schedule

import com.nimbusds.jose.shaded.gson.Gson
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.*

/*
Denne klassen er brukt for å kunne trigge nytt forsøk på å arkivere søknader som har status FAILED fordi kall for å
hente filer eller kall for å arkivere har feilet.
Klassen leser inn en Json fil gitt miljøparameter FAILED_APPLICATIONS lest inn fra secret archiving-failed. Denne inneholder en liste av feilede søknader.
Dette er en Base64encoded streng. Merk at etter vellykket kjøring må denne tømmes.
 */
@Service
@EnableScheduling
class ResendFailedApplications(
	private val leaderSelectionUtility: LeaderSelectionUtility,
	private val taskListService: TaskListService
) {

	val logger: Logger = LoggerFactory.getLogger(javaClass)

	@Value("\${failedApplications}")
	private var envInput: String? = null

	@Scheduled(cron = "\${cron.startRetryOfFailedArchiving}")
	fun start() {
		val inputString: String? = envInput ?: System.getenv("failedApplications") ?: System.getProperty("failedApplications")
		logger.info("**** Start resendFailedApplications, ${if (inputString != null) inputString.length else null} ****")
		try {
			if (leaderSelectionUtility.isLeader() && inputString != null) {
				val gson = Gson()
				val input = gson.fromJson(String(Base64.getDecoder().decode(inputString)), ApplicationList::class.java)

				input.innsendingIds.forEach {
					retry(it)
				}
			} else {
				logger.info("**** Sending skipped ****")
			}
		} catch (ex: Exception) {
			logger.warn("resendFailedApplications feilet med ${ex.message}")
		}
	}

	private fun retry(innsendingsId: String) {
		logger.info("$innsendingsId: Performing forced rerun")

		taskListService.startPaNytt(innsendingsId)
	}

}

