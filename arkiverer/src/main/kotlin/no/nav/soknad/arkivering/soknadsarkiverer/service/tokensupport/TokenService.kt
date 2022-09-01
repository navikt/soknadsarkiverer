package no.nav.soknad.arkivering.soknadsarkiverer.service.tokensupport

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService

class TokenService(val clientProperties: ClientProperties, val oauth2TokenService: OAuth2AccessTokenService) {
	fun getToken() = oauth2TokenService.getAccessToken(clientProperties)
}
