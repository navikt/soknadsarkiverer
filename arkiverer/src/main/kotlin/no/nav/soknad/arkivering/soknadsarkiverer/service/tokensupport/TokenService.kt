package no.nav.soknad.arkivering.soknadsarkiverer.service.tokensupport

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService

class TokenService(private val clientProperties: ClientProperties, private val oauth2TokenService: OAuth2AccessTokenService) {
	fun getToken(): OAuth2AccessTokenResponse? = oauth2TokenService.getAccessToken(clientProperties)
}
