package no.nav.soknad.arkivering.soknadsarkiverer.service.safservice

import com.expediagroup.graphql.client.spring.GraphQLWebClient
import com.expediagroup.graphql.client.types.GraphQLClientError
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkiverer.saf.generated.HentJournalpostGittEksternReferanseId
import no.nav.soknad.arkiverer.saf.generated.hentjournalpostgitteksternreferanseid.Journalpost
import no.nav.soknad.arkivering.soknadsarkiverer.Constants
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class SafService(
	@param:Qualifier("safWebClient") private val graphQLClient: GraphQLWebClient,
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
			val response = graphQLClient.execute(
				HentJournalpostGittEksternReferanseId(
					HentJournalpostGittEksternReferanseId.Variables(
						journalpostId = null,
						eksternReferanseId = innsendingId
					)
				)
			) {
				header(Constants.CORRELATION_ID, innsendingId)
				header(Constants.HEADER_CALL_ID, innsendingId)
			}
			response.errors?.let  {
				handleErrors(innsendingId, response.errors!!, "saf")
				return null
			}
			return response.data?.journalpost ?: run {
				logger.debug("$innsendingId: Ikke funnet i arkivet")
				null
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
		logger.debug("$innsendingId: Oppslag mot $system feilet med $errorMessage")
	}

}
