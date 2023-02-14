package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import no.nav.soknad.arkivering.soknadsarkiverer.utility.LeaderSelectionUtility
import org.springframework.context.annotation.Configuration

@Configuration
class TopicSelection(private val leaderElection: LeaderSelectionUtility) {


	public fun selectTopicVersion(topicTypes: TopicTypes, kafkaConfig: KafkaConfig): String {
		if (leaderElection.isLeader()) {
			when(topicTypes) {
				TopicTypes.MAIN_TOPIC -> return kafkaConfig.topics.mainTopic_old
				TopicTypes.PROCESSING_TOPIC -> return kafkaConfig.topics.processingTopic_old
				TopicTypes.MESSAGES_TOPIC -> return kafkaConfig.topics.messageTopic_old
				TopicTypes.METRICS_TOPIC -> return  kafkaConfig.topics.metricsTopic_old
			}
		} else {
			when(topicTypes) {
				TopicTypes.MAIN_TOPIC -> return kafkaConfig.topics.mainTopic
				TopicTypes.PROCESSING_TOPIC -> return kafkaConfig.topics.processingTopic
				TopicTypes.MESSAGES_TOPIC -> return kafkaConfig.topics.messageTopic
				TopicTypes.METRICS_TOPIC -> return  kafkaConfig.topics.metricsTopic
			}
		}
	}

}
