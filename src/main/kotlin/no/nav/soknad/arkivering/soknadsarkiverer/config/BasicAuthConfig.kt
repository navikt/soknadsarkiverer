package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder

@Configuration
class BasicAuthConfig(private val config: AppConfiguration) {


}
