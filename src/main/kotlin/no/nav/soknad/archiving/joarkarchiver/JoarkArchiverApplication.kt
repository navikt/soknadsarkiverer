package no.nav.soknad.archiving.joarkarchiver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class JoarkArchiverApplication

fun main(args: Array<String>) {
	runApplication<JoarkArchiverApplication>(*args)
}
