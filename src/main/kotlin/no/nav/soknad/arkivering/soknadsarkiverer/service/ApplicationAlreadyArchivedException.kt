package no.nav.soknad.arkivering.soknadsarkiverer.service

import java.lang.RuntimeException

class ApplicationAlreadyArchivedException(message: String): RuntimeException(message)
