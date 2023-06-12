package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.api.HealthApi
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer
import no.nav.soknad.innsending.api.HentInnsendteFilerApi
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FilestorageApiConfig {

	@Bean
	fun filesApi(filestorageProperties: FilestorageProperties, @Qualifier("filestorageClient") filestorageClient: OkHttpClient): FilesApi {
		Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		return FilesApi(filestorageProperties.host, filestorageClient)
	}

	@Bean
	fun hentInnsendteFilerApi(innsendingApiProperties: InnsendingApiProperties, @Qualifier("innsendingApiClient") innsendingApiClient: OkHttpClient): HentInnsendteFilerApi {
		Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		return HentInnsendteFilerApi(innsendingApiProperties.host, innsendingApiClient)
	}

	@Bean
	fun healthApi(filestorageProperties: FilestorageProperties) = HealthApi(filestorageProperties.host)

	@Bean
	fun innsenderHealthApi(innsendingApiProperties: InnsendingApiProperties) = no.nav.soknad.innsending.api.HealthApi(innsendingApiProperties.host)
}
