package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.api.HealthApi
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer
import okhttp3.OkHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FilestorageApiConfig {

	@Bean
	fun filesApi(filestorageProperties: FilestorageProperties, filestorageClient: OkHttpClient): FilesApi {
		Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		return FilesApi(filestorageProperties.host, filestorageClient)
	}

	@Bean
	fun healthApi(filestorageProperties: FilestorageProperties) = HealthApi(filestorageProperties.host)
}
