package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.soknadsarkiverer.Constants.BEARER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.util.concurrent.TimeUnit

fun loopAndVerify(expectedCount: Int, getCount: () -> Int,
									finalCheck: () -> Any = { assertEquals(expectedCount, getCount.invoke()) }) {
	loopAndVerify(getCount, expectedCount, finalCheck) { a, b -> a == b }
}

fun loopAndVerifyAtLeast(expectedCount: Int, getCount: () -> Int,
												 finalCheck: () -> Any = {
													 val actual = getCount.invoke()
													 assertTrue(expectedCount <= actual, "Expected $expectedCount, was $actual")
												 }) {
	loopAndVerify(getCount, expectedCount, finalCheck) { a, b -> a <= b }
}

private fun loopAndVerify(getCount: () -> Int, expectedCount: Int, finalCheck: () -> Any, compareMethod: (Int, Int) -> Boolean) {
	val startTime = System.currentTimeMillis()
	val timeout = 30 * 1000

	while (System.currentTimeMillis() < startTime + timeout) {
		val matches = getCount.invoke()

		if (compareMethod.invoke(expectedCount, matches))
			break
		TimeUnit.MILLISECONDS.sleep(50)
	}
	finalCheck.invoke()
}

infix fun <A> A.hasCount(count: Int) = this to count

fun createHeaders(token: String?, contentType: MediaType): HttpHeaders {
	val headers = HttpHeaders()
	headers.contentType = contentType
	if (token != null) {
		headers.add(HttpHeaders.AUTHORIZATION, "$BEARER$token")
	}
	return headers
}
