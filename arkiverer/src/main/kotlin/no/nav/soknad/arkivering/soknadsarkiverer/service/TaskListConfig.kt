package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(TaskListProperties::class)
class TaskListConfig {

	@Autowired
	private lateinit var taskListProperties: TaskListProperties

	@Bean
	fun taskListService(
		archiverService: ArchiverService,
		applicationState: ApplicationState,
		scheduler: Scheduler,
		metrics: ArchivingMetrics,
		kafkaPublisher: KafkaPublisher
	) = TaskListService(
		archiverService,
		taskListProperties.startUpSeconds,
		taskListProperties.secondsBetweenRetries,
		applicationState, scheduler, metrics, kafkaPublisher
	)
}

@ConfigurationProperties("services.tasklist.scheduling")
@ConstructorBinding
data class TaskListProperties(
	val startUpSeconds: Long,
	val secondsBetweenRetries: List<Long>
)
