package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.soknadsarkiverer.schedule.LeaderSelectionUtility
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Configuration
class SelfDestructConfig(private val scheduler: Scheduler,
												 private val appState: ApplicationState,
												 private val leaderSelectionUtility: LeaderSelectionUtility,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	/**
	 * Tl;dr: When Kubernetes scales down the number of pods, the tasks that the killed pod was working on could remain
	 * unhandled. This self-destruct mechanism is implemented to deal with that corner case.
	 *
	 * == Problem overview ==
	 * In order to ensure that no tasks remain unhandled, this class makes sure to restart pods every night. As part of
	 * the startup process for the application, it will read through the history of events that have been seen, and will
	 * recreate tasks for any events that were not yet finished. Below is a more in-depth description of the problem.
	 *
	 *
	 * === Kafka topics and tasks ===
	 * The application subscribes to [kafkaMainTopic] and [kafkaProcessingTopic]. When an event comes in on the former
	 * topic, a [no.nav.soknad.arkivering.avroschemas.ProcessingEvent] is created on the latter topic, to keep track of
	 * how far into the processing the application has come. An internal, in-memory task is also created in the
	 * [no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService]. The events on the Kafka topics are typically
	 * consumed straight away and the new topic offset is committed to the Kafka broker.
	 *
	 * An event on the [kafkaProcessingTopic] directly corresponds to the status of a task. Once a task has been properly
	 * archived, it gets the status FINISHED.
	 *
	 *
	 * === Starting and shutting down pods ===
	 * The application runs in many different pods, as configured in .nais/nais.yml, where the standard configuration is
	 * 2 pods minimum, and 4 maximum. If a pod restarts, the freshly started pod will - through
	 * [no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping.KafkaBootstrapConsumer] - read through the events on
	 * [kafkaProcessingTopic] and recreate tasks for the events that were not FINISHED.
	 * In other words, if pod 1 is working on tasks A and B; and pod 2 is working on tasks C and D, and pod 2 crashes,
	 * then Kubernetes will restart the pod since only one running pod is below the minimum. The restarted pod will
	 * recreate tasks C and D.
	 *
	 *
	 * === Problem ===
	 * The problem is that there's a caveat in that tasks will only be picked up as a pod is starting up. This is because
	 * the Kafka events on the [kafkaProcessingTopic] are consumed (and the offset committed to the Kafka Broker) by the
	 * same pod that created them, directly after being published to the topic. There are thus no Kafka events to be
	 * consumed by other pods after a pod shuts down and a rebalancing of the Kafka topics takes place. The recreation of
	 * tasks only happens during application startup, by
	 * [no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping.KafkaBootstrapConsumer]
	 *
	 * In other words, lets say that pod 1 is working on tasks A and B; and pod 2 is working on tasks C and D; and pod 3
	 * is working on tasks E and F. If Kubernetes decides to downscale the number of running pods from three to two
	 * because the load is no longer high, then tasks E and F will not be picked up by pod 1 or 2, since they just
	 * continue to run like before. Unless the retention time of the Kafka topics are infinite, there is now a risk that
	 * no pod will start before the Kafka events behind tasks E and F are removed from the topics.
	 *
	 *
	 * === Solution ===
	 * The (ugly) solution to this bug is to shut down the pods every night (self-destruct), which will cause Kubernetes
	 * to restart the pods, thus triggering abandoned tasks to be picked up again.
	 */
	@Profile("prod | dev")
	@Bean
	fun scheduleSelfDestruct() {
		setUpSelfDestructTime()
	}

	private fun setUpSelfDestructTime() {
		val time = timeTomorrowNightBetween2and5()
		logger.info("Will self-destruct at $time")
		scheduler.scheduleSingleTask({ selfDestruct() }, time)
	}

	/**
	 * Self-destruct the next date, somewhere random between 02:00 and 05:00. Set it to random because we don't want
	 * to bring down all running pods at once - the randomness will make it probable that they self-destruct at
	 * different times during the night, meaning that it is very likely that there is at least one other pod running.
	 */
	private fun timeTomorrowNightBetween2and5(): Instant {
		val nextMidnight = LocalDateTime.now() // TODO remove
		val selfDestructTime = nextMidnight.plusHours(0).plusMinutes((0..1 * 60).random().toLong()) // TODO remove
		//val nextMidnight = LocalDate.now().atStartOfDay().plusDays(1) // TODO add
		//val selfDestructTime = nextMidnight.plusHours(2).plusMinutes((0..3 * 60).random().toLong())  // TODO add
		return selfDestructTime.toInstant(ZoneId.of("Europe/Oslo").rules.getOffset(Instant.now()))
	}

	private fun selfDestruct() {
		if (leaderSelectionUtility.isLeader()) {
			logger.info("Initialising self-destruction sequence")
			appState.alive = false
		} else {
			logger.info("Not leader, schedule new self-destruct time")
			setUpSelfDestructTime()
		}
	}
}
