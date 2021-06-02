package no.nav.soknad.arkivering.soknadsarkiverer.admin

data class Metrics(val application: String, val action: String, val startTime: Long, val duration: Long)
data class MetricsObject(val key: String, val datapoints: List<Metrics>)
