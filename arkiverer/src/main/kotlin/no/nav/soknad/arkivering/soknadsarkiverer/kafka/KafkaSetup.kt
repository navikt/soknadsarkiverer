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
	private val metrics: ArchivingMetrics,
	private val kafkaConfig: KafkaConfig
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostConstruct
	fun setupKafka() {
		logger.debug("Setting up Kafka Bootstrapping and Kafka Streams")
		setupMetricsAndHealth(metrics, appConfiguration)

		scheduleJob {
			bootstrapKafka()
			setupKafkaStreams(appConfiguration, taskListService, kafkaPublisher, kafkaConfig = kafkaConfig )
		}
	}

	private fun bootstrapKafka() {
		val startTime = System.currentTimeMillis()
		logger.info("Starting Kafka Bootstrap Consumer to create tasks for any tasks that had not yet been finished")
		KafkaBootstrapConsumer( taskListService, kafkaConfig).recreateState()
		logger.info("Finished Kafka Bootstrap Consumer in ${System.currentTimeMillis() - startTime} ms.")
	}

	private fun scheduleJob(jobToRun: () -> Unit) {
		val delay = kafkaConfig.delayBeforeKafkaInitialization
		scheduler.scheduleSingleTask(jobToRun, Instant.now().plusSeconds(delay))
	}
}

@Service
@Profile("test")
class KafkaSetupTest(
	private val appConfiguration: AppConfiguration,
	private val taskListService: TaskListService,
	private val kafkaPublisher: KafkaPublisher,
	private val metrics: ArchivingMetrics,
	private val kafkaConfig : KafkaConfig
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostConstruct
	fun setupKafka() {
		logger.debug("Setting up Kafka Streams")

		setupMetricsAndHealth(metrics, appConfiguration)

		val groupId = kafkaConfig.applicationId + "_" + UUID.randomUUID().toString()
		setupKafkaStreams(appConfiguration, taskListService, kafkaPublisher, groupId,kafkaConfig)
	}
}

private fun setupKafkaStreams(
	appConfiguration: AppConfiguration,
	taskListService: TaskListService,
	kafkaPublisher: KafkaPublisher,
	groupId: String? = null,
	kafkaConfig : KafkaConfig
) {
	val id = groupId ?: kafkaConfig.applicationId
	KafkaStreamsSetup(appConfiguration, taskListService, kafkaPublisher,kafkaConfig).setupKafkaStreams(id)
}

private fun setupMetricsAndHealth(metrics: ArchivingMetrics, appConfiguration: AppConfiguration) {
	metrics.setUpOrDown(1.0)
	appConfiguration.state.alive = true
	appConfiguration.state.ready = true
}
