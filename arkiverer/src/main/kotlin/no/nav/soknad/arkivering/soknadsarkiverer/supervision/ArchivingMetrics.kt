package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.prometheus.metrics.core.datapoints.Timer
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.core.metrics.Summary
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Component
class ArchivingMetrics(private val registry: PrometheusRegistry) {

	private val SOKNAD_NAMESPACE = "soknadinnsending"
	private val APP_LABEL = "app"
	private val TEMA_LABEL = "tema"
	private val APP = "soknadsarkiverer"


	private val GAUGE_TASKS = "gauge_tasks"
	private val GAUGE_TASKS_DESC = "Number of tasks in progress"

	private val GAUGE_TASKS_GIVEN_UP_ON = "gauge_tasks_given_up_on"
	private val GAUGE_TASKS_GIVEN_UP_ON_DESC = "Number of tasks given up on"

	private val GAUGE_UP_DOWN = "gauge_up_or_downn"
	private val GAUGE_UP_DOWN_DESC = "Up or down status"

	private val SUMMARY_ARCHIVING_LATENCY = "latency_archiving_operations"
	private val SUMMARY_ARCHIVING_LATENCY_DESC = "Latency for archiving"

	private val COUNTER_FILESTORAGE_GET_SUCCESS = "counter_filestorage_get_success"
	private val COUNTER_FILESTORAGE_GET_SUCCESS_DESC = "Number of successful file retrievals from filestorage"

	private val COUNTER_FILESTORAGE_GET_ERROR = "counter_filestorage_get_error"
	private val COUNTER_FILESTORAGE_GET_ERROR_DESC = "Number of failing file retrievals from filestorage"

	private val COUNTER_FILESTORAGE_DEL_SUCCESS = "counter_filestorage_del_success"
	private val COUNTER_FILESTORAGE_DEL_SUCCESS_DESC = "Number of successful file deletions from filestorage"

	private val COUNTER_FILESTORAGE_DEL_ERROR = "counter_filestorage_del_error"
	private val COUNTER_FILESTORAGE_DEL_ERROR_DESC = "Number of failing file deletions from filestorage"

	private val SUMMARY_FILESTORAGE_GET_LATENCY = "latency_filestorage_get_operations"
	private val SUMMARY_FILESTORAGE_GET_LATENCY_DESC = "Latency for retrieving from filestorage"

	private val SUMMARY_FILESTORAGE_DEL_LATENCY = "latency_filestorage_del_operations"
	private val SUMMARY_FILESTORAGE_DEL_LATENCY_DESC = "Latency for deleting from filestorage"

	private val SUMMARY_FILE_FETCH_SIZE = "file_fetch_size"
	private val SUMMARY_FILE_FETCH_SIZE_DESC = "Size of fetched file"
	private val HISTOGRAM_FILE_FETCH_SIZE = "file_fetch_size_distribution"
	private val HISTOGRAM_FILE_FETCH_SIZE_DESC = "Distribution of sizes of fetched files"

	private val COUNTER_JOARK_SUCCESS = "counter_joark_success"
	private val COUNTER_JOARK_SUCCESS_DESC = "Number of successes when sending to Joark"

	private val COUNTER_JOARK_ERROR = "counter_joark_error"
	private val COUNTER_JOARK_ERROR_DESC = "Number of errors when sending to Joark"

	private val SUMMARY_JOARK_LATENCY = "latency_joark_operations"
	private val SUMMARY_JOARK_LATENCY_DESC = "Latency for sending to Joark"

	private val HISTOGRAM_ARCHIVING_LATENCY = "histogram_latency_archiving_operations"
	private val HISTORGRAM_ARCHIVING_LATENCY_DESC = "Histogram for latency for archiving"

	private val taskGauge: Gauge = registerGauge(GAUGE_TASKS, GAUGE_TASKS_DESC)
	private val tasksGivenUpOnGauge: Gauge = registerGauge(GAUGE_TASKS_GIVEN_UP_ON, GAUGE_TASKS_GIVEN_UP_ON_DESC)
	private val upOrDownGauge: Gauge = registerGauge(GAUGE_UP_DOWN, GAUGE_UP_DOWN_DESC)
	private val archivingLatencySummary = registerSummary(SUMMARY_ARCHIVING_LATENCY, SUMMARY_ARCHIVING_LATENCY_DESC)
	private val filestorageGetSuccessCounter: Counter =
		registerCounter(COUNTER_FILESTORAGE_GET_SUCCESS, COUNTER_FILESTORAGE_GET_SUCCESS_DESC)
	private val filestorageGetErrorCounter: Counter =
		registerCounter(COUNTER_FILESTORAGE_GET_ERROR, COUNTER_FILESTORAGE_GET_ERROR_DESC)
	private val filestorageDelSuccessCounter: Counter =
		registerCounter(COUNTER_FILESTORAGE_DEL_SUCCESS, COUNTER_FILESTORAGE_DEL_SUCCESS_DESC)
	private val filestorageDelErrorCounter: Counter =
		registerCounter(COUNTER_FILESTORAGE_DEL_ERROR, COUNTER_FILESTORAGE_DEL_ERROR_DESC)
	private val filestorageGetLatencySummary =
		registerSummary(SUMMARY_FILESTORAGE_GET_LATENCY, SUMMARY_FILESTORAGE_GET_LATENCY_DESC)
	private val filestorageDelLatencySummary =
		registerSummary(SUMMARY_FILESTORAGE_DEL_LATENCY, SUMMARY_FILESTORAGE_DEL_LATENCY_DESC)
	private val filefetchSizeSummary = registerSummary(SUMMARY_FILE_FETCH_SIZE, SUMMARY_FILE_FETCH_SIZE_DESC)
	private val filefetchSizeHistogram =
		registerFileSizeHistogram(HISTOGRAM_FILE_FETCH_SIZE, HISTOGRAM_FILE_FETCH_SIZE_DESC)
	private val joarkSuccessCounter: Counter = registerCounter(COUNTER_JOARK_SUCCESS, COUNTER_JOARK_SUCCESS_DESC)
	private val joarkErrorCounter: Counter = registerCounter(COUNTER_JOARK_ERROR, COUNTER_JOARK_ERROR_DESC)
	private val joarkLatencySummary = registerSummary(SUMMARY_JOARK_LATENCY, SUMMARY_JOARK_LATENCY_DESC)
	private val archivingLatencyHistogram =
		registerLatencyHistogram(HISTOGRAM_ARCHIVING_LATENCY, HISTORGRAM_ARCHIVING_LATENCY_DESC)

	private val HISTOGRAM_ATTACHMENT_NUMBER = "histogram_attachment_number"
	private val HISTOGRAM_ATTACHMENT_NUMBER_DESC = "Histogram for number of attachment per application"
	private val numberOfAttachmentHistogram =
		registerAttachmentNumberHistogram(HISTOGRAM_ATTACHMENT_NUMBER, HISTOGRAM_ATTACHMENT_NUMBER_DESC)

	private fun registerCounter(name: String, help: String): Counter =
		Counter
			.builder()
			.name("${SOKNAD_NAMESPACE}_$name")
			.help(help)
			.withoutExemplars()
			.labelNames(APP_LABEL)
			.register(registry)

	private fun registerGauge(name: String, help: String): Gauge =
		Gauge
			.builder()
			.name("${SOKNAD_NAMESPACE}_$name")
			.help(help)
			.withoutExemplars()
			.labelNames(APP_LABEL)
			.register(registry)

	private fun registerSummary(name: String, help: String): Summary =
		Summary
			.builder()
			.name("${SOKNAD_NAMESPACE}_$name")
			.help(help)
			.withoutExemplars()
			.quantile(0.5, 0.05)
			.quantile(0.9, 0.01)
			.quantile(0.99, 0.001)
			.labelNames(APP_LABEL)
			.register(registry)

	private fun registerLatencyHistogram(name: String, help: String): Histogram =
		Histogram
			.builder()
			.name("${SOKNAD_NAMESPACE}_$name")
			.help(help)
			.withoutExemplars()
			.classicExponentialUpperBounds(0.1, 2.0, 12)
			.labelNames(TEMA_LABEL)
			.register(registry)

	private fun registerFileSizeHistogram(name: String, help: String): Histogram {
		val kB = 1024.0
		return Histogram
			.builder()
			.name("${SOKNAD_NAMESPACE}_$name")
			.help(help)
			.withoutExemplars()
			.classicExponentialUpperBounds(kB, 2.0, 20)
			.labelNames(TEMA_LABEL)
			.register(registry)
	}

	private fun registerAttachmentNumberHistogram(name: String, help: String): Histogram =
		Histogram
			.builder()
			.name("${SOKNAD_NAMESPACE}_$name")
			.help(help)
			.withoutExemplars()
			.classicLinearUpperBounds(2.0, 1.0, 12)
			.labelNames(TEMA_LABEL)
			.register(registry)

	fun incGetFilestorageSuccesses() = filestorageGetSuccessCounter.labelValues(APP).inc()
	fun getGetFilestorageSuccesses() = filestorageGetSuccessCounter.labelValues(APP).get()

	fun incGetFilestorageErrors() = filestorageGetErrorCounter.labelValues(APP).inc()
	fun getGetFilestorageErrors() = filestorageGetErrorCounter.labelValues(APP).get()

	fun incDelFilestorageSuccesses() = filestorageDelSuccessCounter.labelValues(APP).inc()
	fun getDelFilestorageSuccesses() = filestorageDelSuccessCounter.labelValues(APP).get()

	fun incDelFilestorageErrors() = filestorageDelErrorCounter.labelValues(APP).inc()
	fun getDelFilestorageErrors() = filestorageDelErrorCounter.labelValues(APP).get()

	fun incJoarkSuccesses() = joarkSuccessCounter.labelValues(APP).inc()
	fun getJoarkSuccesses() = joarkSuccessCounter.labelValues(APP).get()

	fun incJoarkErrors() = joarkErrorCounter.labelValues(APP).inc()
	fun getJoarkErrors() = joarkErrorCounter.labelValues(APP).get()

	fun addTask() = taskGauge.labelValues(APP).inc()
	fun removeTask() = taskGauge.labelValues(APP).dec()
	fun getTasks() = taskGauge.labelValues(APP).get()

	fun setTasksGivenUpOn(value: Int) = tasksGivenUpOnGauge.labelValues(APP).set(value.toDouble())
	fun getTasksGivenUpOn() = tasksGivenUpOnGauge.labelValues(APP).get()

	fun setUpOrDown(value: Double) = upOrDownGauge.labelValues(APP).set(value)

	fun archivingLatencyStart(): Timer = archivingLatencySummary.labelValues(APP).startTimer()
	fun filestorageGetLatencyStart(): Timer = filestorageGetLatencySummary.labelValues(APP).startTimer()
	fun filestorageDelLatencyStart(): Timer = filestorageDelLatencySummary.labelValues(APP).startTimer()
	fun startJoarkLatency(): Timer = joarkLatencySummary.labelValues(APP).startTimer()
	fun getJoarkLatency() = joarkLatencySummary.collect().dataPoints
	fun archivingLatencyHistogramStart(tema: String): Timer =
		archivingLatencyHistogram.labelValues(tema).startTimer()

	fun setNumberOfAttachmentHistogram(number: Double, tema: String) =
		numberOfAttachmentHistogram.labelValues(tema).observe(number)

	fun getNumberOfAttachmentHistogram(tema: String) =
		numberOfAttachmentHistogram.collect().dataPoints.find { it.labels[TEMA_LABEL] == tema }

	fun setFileFetchSize(size: Double) = filefetchSizeSummary.labelValues(APP).observe(size)
	fun getFileFetchSize() = filefetchSizeSummary.collect().dataPoints
	fun setFileFetchSizeHistogram(size: Double, tema: String) = filefetchSizeHistogram.labelValues(tema).observe(size)
	fun getFileFetchSizeHistogram(tema: String) =
		filefetchSizeHistogram.collect().dataPoints.find { it.labels[TEMA_LABEL] == tema }

	fun endTimer(timer: Timer) {
		timer.observeDuration()
	}

	fun endHistogramTimer(timer: Timer) {
		timer.observeDuration()
	}

	fun unregister() {
		joarkErrorCounter.clear()
		joarkSuccessCounter.clear()
		filestorageDelErrorCounter.clear()
		filestorageDelSuccessCounter.clear()
		filestorageGetErrorCounter.clear()
		filestorageGetSuccessCounter.clear()
		joarkLatencySummary.clear()
		filestorageDelLatencySummary.clear()
		filestorageGetLatencySummary.clear()
		archivingLatencySummary.clear()
		archivingLatencyHistogram.clear()
		tasksGivenUpOnGauge.clear()
		setTasksGivenUpOn(0)
		taskGauge.clear()
		upOrDownGauge.clear()
		setUpOrDown(0.0)
		numberOfAttachmentHistogram.clear()
		filefetchSizeSummary.clear()

		registry.unregister(joarkErrorCounter)
		registry.unregister(joarkSuccessCounter)
		registry.unregister(filestorageDelErrorCounter)
		registry.unregister(filestorageDelSuccessCounter)
		registry.unregister(filestorageGetErrorCounter)
		registry.unregister(filestorageGetSuccessCounter)
		registry.unregister(joarkLatencySummary)
		registry.unregister(filestorageDelLatencySummary)
		registry.unregister(filestorageGetLatencySummary)
		registry.unregister(archivingLatencySummary)
		registry.unregister(archivingLatencyHistogram)
		registry.unregister(tasksGivenUpOnGauge)
		registry.unregister(taskGauge)
		registry.unregister(upOrDownGauge)
		registry.unregister(numberOfAttachmentHistogram)
		registry.unregister(filefetchSizeSummary)
		registry.unregister(filefetchSizeHistogram)
	}


}
