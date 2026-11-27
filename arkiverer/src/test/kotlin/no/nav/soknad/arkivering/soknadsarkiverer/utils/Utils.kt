package no.nav.soknad.arkivering.soknadsarkiverer.utils

import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.TimeUnit

fun loopAndVerify(expectedCount: Int, getCount: () -> Int,
									finalCheck: () -> Any = { assertEquals(expectedCount, getCount.invoke()) }) {
	loopAndVerify(getCount, expectedCount, finalCheck) { a, b -> a == b }
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
