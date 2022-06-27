package no.nav.soknad.arkivering.soknadsarkiverer.config

@Synchronized
fun busyInc(appState : ApplicationState): Boolean {
	return if (appState.stopping) {
		false
	} else {
		appState.busyCounter++
		true
	}
}

@Synchronized
fun busyDec(appState : ApplicationState) {
	if (appState.busyCounter > 0) appState.busyCounter--
}

@Synchronized
fun stop(appState : ApplicationState) {
	appState.stopping = true
}

@Synchronized
fun isBusy(appState : ApplicationState) = appState.busyCounter > 0

/**
 * This will execute the given function. If the application receives a signal to shut down during the execution,
 * the function will still finish executing before the application is shut down.
 *
 * If the application had already received a signal to shut down before this method was called, a ShuttingDownException
 * will be thrown and the given function will not be called.
 *
 * @return the value from the provided function.
 * @throws ShuttingDownException if the application had already received a signal to shut down before this method was called.
 */
fun <T> protectFromShutdownInterruption(appState : ApplicationState, function: () -> T): T {
	if (busyInc(appState)) {
		try {
			return function.invoke()
		} finally {
			busyDec(appState)
		}
	}
	throw ShuttingDownException("Application is shutting down")
}
