package no.nav.soknad.arkivering.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ArchivalData(@JsonProperty("id") val id: String, @JsonProperty("message") val message: String)
