package no.nav.soknad.arkivering.soknadsarkiverer.config

@Synchronized
fun busyInc(appConfiguration: AppConfiguration): Boolean {
	return if (appConfiguration.state.stopping) {
		false
	} else {
		appConfiguration.state.busyCounter++
		true
	}
}

@Synchronized
fun busyDec(appConfiguration: AppConfiguration) {
	if (appConfiguration.state.busyCounter > 0) appConfiguration.state.busyCounter--
}

@Synchronized
fun stop(appConfiguration: AppConfiguration) {
	appConfiguration.state.stopping = true
}

@Synchronized
fun isBusy(appConfiguration: AppConfiguration) = appConfiguration.state.busyCounter > 0

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
fun <T> protectFromShutdownInterruption(appConfiguration: AppConfiguration, function: () -> T): T {
	if (busyInc(appConfiguration)) {
		val returnValue = function.invoke()
		busyDec(appConfiguration)
		return returnValue
	}
	throw ShuttingDownException("Application is shutting down")
}
