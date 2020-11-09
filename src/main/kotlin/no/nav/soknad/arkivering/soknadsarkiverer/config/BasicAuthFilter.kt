package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import javax.servlet.*

class BasicAuthFilter(): Filter {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Throws(IOException::class, ServletException::class)
	override fun doFilter(request: ServletRequest, response: ServletResponse, filterChain: FilterChain) {
		logger.info("I basicAuthFilter")

		filterChain.doFilter(request, response)
	}

}
