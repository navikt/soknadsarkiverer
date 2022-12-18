package no.nav.soknad.arkivering.soknadsarkiverer.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException

@Component
class CORSFilter : Filter {
	private val logger = LoggerFactory.getLogger(javaClass)

	override fun doFilter(servletRequest: ServletRequest, servletResponse: ServletResponse, filterChain: FilterChain) {
		val httpServletResponse: HttpServletResponse = servletResponse as HttpServletResponse
		val httpServletRequest: HttpServletRequest = servletRequest as HttpServletRequest

		httpServletResponse.setHeader("Access-Control-Allow-Origin", "*")
		httpServletResponse.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
		httpServletResponse.setHeader("Access-Control-Allow-Headers", "accept, authorization, X-requested-with, content-type")

		if (httpServletRequest.method == "OPTIONS")
			try {
				httpServletResponse.status = 200
				httpServletResponse.writer.print("OK")
				httpServletResponse.writer.flush()
			} catch (e: IOException) {
				logger.error("Filter error: ${e.message}", e)
			}
		else
			filterChain.doFilter(servletRequest, servletResponse)
	}
}
