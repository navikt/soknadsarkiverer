package no.nav.soknad.arkivering.soknadsarkiverer.utility

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

@Serializable
data class LeaderElection(
	val name: String,
	val last_update: String? = LocalDateTime.now().toString()
)

