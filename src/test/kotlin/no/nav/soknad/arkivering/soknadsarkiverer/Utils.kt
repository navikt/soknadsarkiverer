package no.nav.soknad.arkivering.soknadsarkiverer

import java.util.concurrent.TimeUnit

fun loopAndVerify(expectedCount: Int, getCount: () -> Int,
									finalCheck: () -> Any = { getCount.invoke() == expectedCount }) {
	val startTime = System.currentTimeMillis()
	val timeout = 30 * 1000

	while (System.currentTimeMillis() < startTime + timeout) {
		val matches = getCount.invoke()

		if (matches == expectedCount) {
			break
		}
		TimeUnit.MILLISECONDS.sleep(50)
	}
	finalCheck.invoke()
}
