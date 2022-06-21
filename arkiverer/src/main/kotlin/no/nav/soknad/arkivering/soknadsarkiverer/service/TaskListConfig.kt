package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.awt.FontMetrics

@Configuration
@EnableConfigurationProperties(TaskListProperties::class)
class TaskListConfig {

	@Autowired
  private lateinit var taskListProperties: TaskListProperties

	@Bean
	fun taskListService(archiverService: ArchiverService,
											appConfiguration: AppConfiguration,
											scheduler: Scheduler,
											metrics: ArchivingMetrics,
											kafkaPublisher: KafkaPublisher) = TaskListService(archiverService,
																																					startUpSeconds = taskListProperties.startUpSeconds,
																																					secondsBetweenRetries = taskListProperties.secondsBetweenRetries,
																																					appConfiguration,
																																					scheduler,metrics
																																					,kafkaPublisher)

}
@ConfigurationProperties("services.tasklist.scheduling")
class TaskListProperties {

	lateinit var startUpSeconds: String

	lateinit var secondsBetweenRetries :Array<Int>
}
