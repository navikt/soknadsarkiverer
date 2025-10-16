package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.expediagroup.graphql.client.spring.GraphQLWebClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class SafClientConfig(
	@param:Qualifier("safWebClientBuilder") private val safWebClientBuilder: WebClient.Builder,
	@param:Value("\${saf.url}") private val safUrl: String,
	@param:Value("\${saf.path}") private val queryPath: String
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Bean
	@Qualifier("safWebClient")
	fun getSafWebClient(): GraphQLWebClient {
		return  GraphQLWebClient(
			url = "${safUrl}${queryPath}",
			builder = safWebClientBuilder.clone()
		)
	}
}
