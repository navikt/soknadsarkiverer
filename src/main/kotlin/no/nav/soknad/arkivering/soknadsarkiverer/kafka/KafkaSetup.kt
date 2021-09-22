package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping.KafkaBootstrapConsumer
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import javax.annotation.PostConstruct


@Service
@Profile("!test")
class KafkaSetup(
	private val appConfiguration: AppConfiguration,
	private val taskListService: TaskListService,
	private val kafkaPublisher: KafkaPublisher,
	private val scheduler: Scheduler,
	private val metrics: ArchivingMetrics
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostConstruct
	fun setupKafka() {
		logger.debug("Setting up Kafka Bootstrapping and Kafka Streams")
		setupMetricsAndHealth(metrics, appConfiguration)

		scheduleJob {
			bootstrapKafka()
			setupKafkaStreams(appConfiguration, taskListService, kafkaPublisher)
		}
	}

	private fun bootstrapKafka() {
		logger.info("Starting Kafka Bootstrap Consumer to create tasks for any tasks that had not yet been finished")
		KafkaBootstrapConsumer(appConfiguration, taskListService).recreateState()
		logger.info("Finished Kafka Bootstrap Consumer")
	}

	private fun scheduleJob(jobToRun: () -> Unit) {
		val delay = appConfiguration.kafkaConfig.delayBeforeKafkaInitialization.toLong()
		scheduler.scheduleSingleTask(jobToRun, Instant.now().plusSeconds(delay))
	}
}

@Service
@Profile("test")
class KafkaSetupTest(
	private val appConfiguration: AppConfiguration,
	private val taskListService: TaskListService,
	private val kafkaPublisher: KafkaPublisher,
	private val metrics: ArchivingMetrics
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostConstruct
	fun setupKafka() {
		logger.debug("Setting up Kafka Streams")

		setupMetricsAndHealth(metrics, appConfiguration)

		val groupId = appConfiguration.kafkaConfig.groupId + "_" + UUID.randomUUID().toString()
		setupKafkaStreams(appConfiguration, taskListService, kafkaPublisher, groupId)
	}
}

private fun setupKafkaStreams(
	appConfiguration: AppConfiguration,
	taskListService: TaskListService,
	kafkaPublisher: KafkaPublisher,
	groupId: String? = null
) {
	val id = groupId ?: appConfiguration.kafkaConfig.groupId
	KafkaStreamsSetup(appConfiguration, taskListService, kafkaPublisher).setupKafkaStreams(id)
}

private fun setupMetricsAndHealth(metrics: ArchivingMetrics, appConfiguration: AppConfiguration) {
	metrics.setUpOrDown(1.0)
	appConfiguration.state.started = true
	appConfiguration.state.alive = true
	appConfiguration.state.ready = true
}