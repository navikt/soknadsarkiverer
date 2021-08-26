package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping.KafkaBootstrapConsumer
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import javax.annotation.PostConstruct


@Service
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
		setupMetricsAndHealth()

		scheduleJob {
			bootstrapKafka()
			setupKafkaStreams()
		}
	}

	private fun bootstrapKafka() {
		logger.info("Starting Kafka Bootstrap Consumer to create tasks for any tasks that had not yet been finished")
		KafkaBootstrapConsumer(appConfiguration, taskListService).recreateState()
		logger.info("Finished Kafka Bootstrap Consumer")
	}

	private fun setupKafkaStreams() {
		KafkaStreamsSetup(appConfiguration, taskListService, kafkaPublisher).setupKafkaStreams()
	}


	private fun setupMetricsAndHealth() {
		metrics.setUpOrDown(1.0)
		appConfiguration.state.started = true
		appConfiguration.state.alive = true
		appConfiguration.state.ready = true
	}

	private fun scheduleJob(jobToRun: () -> Unit) {
		val delay = appConfiguration.kafkaConfig.delayBeforeKafkaInitialization.toLong()
		scheduler.scheduleSingleTask(jobToRun, Instant.now().plusSeconds(delay))
	}
}
