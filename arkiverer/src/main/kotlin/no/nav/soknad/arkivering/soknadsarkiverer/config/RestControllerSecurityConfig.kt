package no.nav.soknad.arkivering.soknadsarkiverer.config

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

@Configuration
@EnableGlobalMethodSecurity(securedEnabled = true, prePostEnabled = true)
class RestControllerSecurityConfig(private val config: AppConfiguration) : WebSecurityConfigurerAdapter() {

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


	@Autowired
	fun configureGlobal(auth: AuthenticationManagerBuilder) {
		auth.inMemoryAuthentication()
			.withUser(config.config.innsendingUsername)
			.password("{noop}${config.config.innsendingPassword}")
			.roles("USER", "ADMIN")
			.and()
			.withUser(config.config.adminUser)
			.password("{noop}${config.config.adminUserPassword}")
			.roles("USER", "ADMIN")
	}

	@Bean
	fun authenticationEntryPoint(): AuthenticationEntryPoint {
		val entryPoint = BasicAuthenticationEntryPoint()
		entryPoint.realmName = "admin realm"
		return entryPoint
	}
}
