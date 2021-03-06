package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.PathRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint

// Det er mulig denne filen kan slettes i og med at vi bruker token, og ikke basic aut til å beskytte rest endepunktene.
@Configuration
@EnableGlobalMethodSecurity(securedEnabled = true, prePostEnabled = true)
class RestControllerSecurityConfig(private val config: AppConfiguration) : WebSecurityConfigurerAdapter() {

	private val logger = LoggerFactory.getLogger(javaClass)

	override fun configure(http: HttpSecurity) {
		logger.info("In configure")
		http
			.csrf().disable()
			.authorizeRequests()
			.requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
			.antMatchers(HttpMethod.POST, "/login", "/register").permitAll()
			.antMatchers(HttpMethod.GET, "/internal/**").permitAll()
			.antMatchers(HttpMethod.GET, "/swagger-ui.html/**").permitAll()
			.and()
			.httpBasic().authenticationEntryPoint(authenticationEntryPoint())
			.and()
			.sessionManagement()
			.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
	}


	@Autowired
	fun configureGlobal(auth: AuthenticationManagerBuilder) {
		logger.info("In configureGlobal")
		val user = config.config.username
		val password = config.config.sharedPassword
		auth.inMemoryAuthentication()
			.withUser(user)
			.password("{noop}$password")
			.roles("USER", "ADMIN")
			.and()
			.withUser(config.config.adminUser)
			.password("{noop}${config.config.adminUserPassword}")
			.roles("USER", "ADMIN")
	}

	@Bean
	fun authenticationEntryPoint(): AuthenticationEntryPoint? {
		val entryPoint = BasicAuthenticationEntryPoint()
		entryPoint.realmName = "admin realm"
		return entryPoint
	}
}
