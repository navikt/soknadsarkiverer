FROM navikt/java:11
ENV APPLICATION_PROFILE="docker"
ENV KAFKA_BOOTSTRAP_SERVERS=kafka-broker:29092
ENV SCHEMA_REGISTRY_URL=http://kafka-schema-registry:8081

COPY target/*SNAPSHOT.jar /app.jar

# The purpose of the sleep is to make sure the Kafka Broker has time to start up before we try to connect to it
CMD sleep 30 && java -jar /app.jar
