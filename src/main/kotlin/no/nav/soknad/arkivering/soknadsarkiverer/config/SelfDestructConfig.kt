package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Configuration
class SelfDestructConfig(private val scheduler: Scheduler, private val appConfiguration: AppConfiguration) {
	private val logger = LoggerFactory.getLogger(javaClass)

	/**
	 * The application runs in many different pods, as configured in .nais/nais.yml, where the standard configuration is
	 * 2 pods minimum, and 4 maximum. The application has an internal list of tasks that it works on (see
	 * [no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService]). If a pod restarts, the tasks it was working
	 * on will be picked up by the freshly started pod, through
	 * [no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping.KafkaBootstrapConsumer]. In other words,
	 * if pod 1 is working on tasks A and B; and pod 2 is working on tasks C and D, and pod 2 crashes, then Kubernetes
	 * will restart the pod since only one running pod is below the minimum. The restarted pod will pick up tasks C and D.
	 *
	 * However, there is a caveat in that tasks will only be picked up as a pod is starting up. So, let's say that
	 * pod 1 is working on tasks A and B; and pod 2 is working on tasks C and D; and pod 3 is working on tasks E and F.
	 * If Kubernetes decides to downscale the number of running pods from three to two because the load is no longer high,
	 * then tasks E and F will not be picked up by pod 1 or 2, since they just continue to run like before. Unless the
	 * retention time of the Kafka topics are infinite, there is now a risk that no pod will start before the Kafka events
	 * behind tasks E and F are removed from the topics.
	 *
	 * The (ugly) solution to this bug is to shut down the pods every night (self destruct), which will cause Kubernetes
	 * to restart the pods, thus triggering abandoned tasks to be picked up again.
	 */
	@Profile("prod | dev")
	@Bean
	fun scheduleSelfDestruct() {
		val time = timeTomorrowNightBetween2and5()
		logger.info("Will self destruct at $time")
		scheduler.scheduleSelfDestruct({ selfDestruct() }, time)
	}

	/**
	 * Self destruct the next date, somewhere random between 02:00 and 05:00. Set it to random because we don't want
	 * to bring down all running pods at once - the randomness will make it probable that they self destruct at
	 * different times during the night, meaning that it is very likely that there is at least one other pod running.
	 */
	private fun timeTomorrowNightBetween2and5(): Instant {
		val nextMidnight = LocalDate.now().atStartOfDay().plusDays(1)
		val selfDestructTime = nextMidnight.plusHours(2).plusMinutes((0..3 * 60).random().toLong())
		return selfDestructTime.toInstant(ZoneId.of("Europe/Oslo").rules.getOffset(Instant.now()))
	}

	private fun selfDestruct() {
		logger.info("Initialising self destruction sequence")
		appConfiguration.state.up = false
	}
}
