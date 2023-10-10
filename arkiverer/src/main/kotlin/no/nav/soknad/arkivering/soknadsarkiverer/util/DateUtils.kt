package no.nav.soknad.arkivering.soknadsarkiverer.util

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

fun konverterTilDateTime(dateString: String): OffsetDateTime {
	if (dateString.isBlank()) return OffsetDateTime.MIN
	val formatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME
	val dateTime = LocalDateTime.parse(dateString, formatter)
	val zoneOffSet = OffsetDateTime.now().offset
	return dateTime.atOffset(zoneOffSet)
}
