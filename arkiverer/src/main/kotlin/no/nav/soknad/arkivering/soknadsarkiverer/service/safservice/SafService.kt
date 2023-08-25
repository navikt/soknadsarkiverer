package no.nav.soknad.arkivering.soknadsarkiverer.service.safservice

import com.expediagroup.graphql.client.spring.GraphQLWebClient
import com.expediagroup.graphql.client.types.GraphQLClientError
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkiverer.saf.generated.HentJournalpostGittEksternReferanseId
import no.nav.soknad.arkiverer.saf.generated.hentjournalpostgitteksternreferanseid.Journalpost
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
class SafService(
	@Qualifier("safWebClient") private val safWebClient: GraphQLWebClient
) : SafServiceInterface
{
	private val logger = LoggerFactory.getLogger(javaClass)

	override fun hentJournalpostGittInnsendingId(innsendingId: String): Journalpost? {
		return runBlocking {
			execute(innsendingId)
		}
	}

	suspend fun execute(innsendingId: String): Journalpost? {
		try {
			val response = safWebClient.execute(
				HentJournalpostGittEksternReferanseId(
					HentJournalpostGittEksternReferanseId.Variables(
						journalpostId = null,
						eksternReferanseId = innsendingId
					)
				)
			)
			if (!response.errors.isNullOrEmpty()) {
				handleErrors(innsendingId, response.errors!!, "saf")
				return null
			}
			if (response.data != null && response.data!!.journalpost != null) {
				return response.data!!.journalpost
			} else {
				logger.info("$innsendingId: Ikke funnet i arkivet")
				return null
			}
		} catch (ex: Exception) {
			logger.warn("$innsendingId: Error calling SAF", ex)
			return null
		}
	}

	fun handleErrors(innsendingId: String, errors: List<GraphQLClientError>, system: String) {
		val errorMessage = errors
			.map { "${it.message} (feilkode: ${it.path} ${it.path?.forEach { e -> e.toString() }}" }
			.joinToString(prefix = "Error i respons fra $system: ", separator = ", ") { it }
		logger.info("$innsendingId: Oppslag mot $system feilet med $errorMessage")
	}

}
