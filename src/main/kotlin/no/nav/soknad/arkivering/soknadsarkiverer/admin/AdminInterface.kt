package no.nav.soknad.arkivering.soknadsarkiverer.admin

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/admin")
class AdminInterface {

	@PostMapping("/rerun/{key}")
	fun rerun(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/allEvents")
	fun allEvents() {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/unfinishedEvents")
	fun unfinishedEvents() {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/{key}")
	fun specificEvent(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/eventContent/{key}")
	fun eventContent(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}

	@GetMapping("/fillager/filesExist/{key}")
	fun filesExists(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}
}
