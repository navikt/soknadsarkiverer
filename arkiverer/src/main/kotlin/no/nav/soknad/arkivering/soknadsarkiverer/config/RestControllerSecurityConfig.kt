package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
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

@Configuration
@EnableGlobalMethodSecurity(securedEnabled = true, prePostEnabled = true)
class RestControllerSecurityConfig(@Value("\${no.nav.security.basic.username}") private val adminUsername : String,
																	 @Value("\${no.nav.security.basic.username}") private val adminPassword: String ) : WebSecurityConfigurerAdapter() {



	@Autowired
	fun configureGlobal(auth: AuthenticationManagerBuilder) {
		auth.inMemoryAuthentication()
			.withUser(adminUsername)
			.password("{noop}${adminPassword}")
			.roles("USER", "ADMIN")
	}


	override fun configure(http: HttpSecurity) {
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

	@Bean
	fun authenticationEntryPoint(): AuthenticationEntryPoint {
		val entryPoint = BasicAuthenticationEntryPoint()
		entryPoint.realmName = "admin realm"
		return entryPoint
	}
}
