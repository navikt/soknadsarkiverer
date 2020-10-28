package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary

internal object Metrics {

	private const val SOKNAD_NAMESPACE = "soknadinnsending"
	private const val SUB_SYSTEM = "soknadsarkiverer"


	private const val GAUGE_TASKS = "gauge_tasks"
	private const val GAUGE_TASKS_DESC = "Number of tasks in progress"

	private const val GAUGE_TASKS_GIVEN_UP_ON = "gauge_tasks_given_up_on"
	private const val GAUGE_TASKS_GIVEN_UP_ON_DESC = "Number of tasks given up on"

	private const val SUMMARY_ARCHIVING_LATENCY = "latency_archiving_operations"
	private const val SUMMARY_ARCHIVING_LATENCY_DESC = "Latency for archiving"

	private const val COUNTER_FILESTORAGE_GET_SUCCESS = "counter_filestorage_get_success"
	private const val COUNTER_FILESTORAGE_GET_SUCCESS_DESC = "Number of successful file retrievals from filestorage"

	private const val COUNTER_FILESTORAGE_GET_ERROR = "counter_filestorage_get_error"
	private const val COUNTER_FILESTORAGE_GET_ERROR_DESC = "Number of failing file retrievals from filestorage"

	private const val COUNTER_FILESTORAGE_DEL_SUCCESS = "counter_filestorage_del_success"
	private const val COUNTER_FILESTORAGE_DEL_SUCCESS_DESC = "Number of successful file deletions from filestorage"

	private const val COUNTER_FILESTORAGE_DEL_ERROR = "counter_filestorage_del_error"
	private const val COUNTER_FILESTORAGE_DEL_ERROR_DESC = "Number of failing file deletions from filestorage"

	private const val SUMMARY_FILESTORAGE_GET_LATENCY = "latency_filestorage_get_operations"
	private const val SUMMARY_FILESTORAGE_GET_LATENCY_DESC = "Latency for retrieving from filestorage"

	private const val SUMMARY_FILESTORAGE_DEL_LATENCY = "latency_filestorage_del_operations"
	private const val SUMMARY_FILESTORAGE_DEL_LATENCY_DESC = "Latency for deleting from filestorage"

	private const val COUNTER_JOARK_SUCCESS = "counter_joark_succerr"
	private const val COUNTER_JOARK_SUCCESS_DESC = "Number of successes when sending to Joark"

	private const val COUNTER_JOARK_ERROR = "counter_joark_error"
	private const val COUNTER_JOARK_ERROR_DESC = "Number of errors when sending to Joark"

	private const val SUMMARY_JOARK_LATENCY = "latency_joark_operations"
	private const val SUMMARY_JOARK_LATENCY_DESC = "Latency for sending to Joark"

	private val taskGauge: Gauge = registerGauge(GAUGE_TASKS, GAUGE_TASKS_DESC)
	private val tasksGivenUpOnGauge: Gauge = registerGauge(GAUGE_TASKS_GIVEN_UP_ON, GAUGE_TASKS_GIVEN_UP_ON_DESC)
	private val archivingLatencySummary = registerSummary(SUMMARY_ARCHIVING_LATENCY, SUMMARY_ARCHIVING_LATENCY_DESC)
	private val filestorageGetSuccessCounter: Counter = registerCounter(COUNTER_FILESTORAGE_GET_SUCCESS, COUNTER_FILESTORAGE_GET_SUCCESS_DESC)
	private val filestorageGetErrorCounter: Counter = registerCounter(COUNTER_FILESTORAGE_GET_ERROR, COUNTER_FILESTORAGE_GET_ERROR_DESC)
	private val filestorageDelSuccessCounter: Counter = registerCounter(COUNTER_FILESTORAGE_DEL_SUCCESS, COUNTER_FILESTORAGE_DEL_SUCCESS_DESC)
	private val filestorageDelErrorCounter: Counter = registerCounter(COUNTER_FILESTORAGE_DEL_ERROR, COUNTER_FILESTORAGE_DEL_ERROR_DESC)
	private val filestorageGetLatencySummary = registerSummary(SUMMARY_FILESTORAGE_GET_LATENCY, SUMMARY_FILESTORAGE_GET_LATENCY_DESC)
	private val filestorageDelLatencySummary = registerSummary(SUMMARY_FILESTORAGE_DEL_LATENCY, SUMMARY_FILESTORAGE_DEL_LATENCY_DESC)
	private val joarkSuccessCounter: Counter = registerCounter(COUNTER_JOARK_SUCCESS, COUNTER_JOARK_SUCCESS_DESC)
	private val joarkErrorCounter: Counter = registerCounter(COUNTER_JOARK_ERROR, COUNTER_JOARK_ERROR_DESC)
	private val joarkLatencySummary = registerSummary(SUMMARY_JOARK_LATENCY, SUMMARY_JOARK_LATENCY_DESC)

	private fun registerCounter(name: String, help: String): Counter =
		Counter
			.build()
			.namespace(SOKNAD_NAMESPACE)
			.subsystem(SUB_SYSTEM)
			.name(name)
			.help(help)
			.register()

	private fun registerGauge(name: String, help: String): Gauge =
		Gauge
			.build()
			.namespace(SOKNAD_NAMESPACE)
			.subsystem(SUB_SYSTEM)
			.name(name)
			.help(help)
			.register()

	private fun registerSummary(name: String, help: String): Summary =
		Summary
			.build()
			.namespace(SOKNAD_NAMESPACE)
			.subsystem(SUB_SYSTEM)
			.name(name)
			.help(help)
			.quantile(0.5, 0.05)
			.quantile(0.9, 0.01)
			.quantile(0.99, 0.001)
			.register()


	fun incGetFilestorageSuccesses() = filestorageGetSuccessCounter.inc()
	fun getGetFilestorageSuccesses() = filestorageGetSuccessCounter.get()

	fun incGetFilestorageErrors() = filestorageGetErrorCounter.inc()
	fun getGetFilestorageErrors() = filestorageGetErrorCounter.get()

	fun incDelFilestorageSuccesses() = filestorageDelSuccessCounter.inc()
	fun getDelFilestorageSuccesses() = filestorageDelSuccessCounter.get()

	fun incDelFilestorageErrors() = filestorageDelErrorCounter.inc()
	fun getDelFilestorageErrors() = filestorageDelErrorCounter.get()

	fun incJoarkSuccesses() = joarkSuccessCounter.inc()
	fun getJoarkSuccesses() = joarkSuccessCounter.get()

	fun incJoarkErrors() = joarkErrorCounter.inc()
	fun getJoarkErrors() = joarkErrorCounter.get()

	fun addTask() = taskGauge.inc()
	fun removeTask() = taskGauge.dec()
	fun getTasks() = taskGauge.get()

	fun setTasksGivenUpOn(value: Double) = tasksGivenUpOnGauge.set(value)
	fun getTasksGivenUpOn() = tasksGivenUpOnGauge.get()

	fun archivingLatencyStart(): Summary.Timer = archivingLatencySummary.startTimer()
	fun filestorageGetLatencyStart(): Summary.Timer = filestorageGetLatencySummary.startTimer()
	fun filestorageDelLatencyStart(): Summary.Timer = filestorageDelLatencySummary.startTimer()
	fun joarkLatencyStart(): Summary.Timer = joarkLatencySummary.startTimer()

	fun endTimer(timer: Summary.Timer) {
		timer.observeDuration()
	}
}
