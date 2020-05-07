FROM navikt/java:11

ENV APPLICATION_PROFILE=test
ENV KAFKA_BOOTSTRAP_SERVERS=kafka-broker:29092
ENV SCHEMA_REGISTRY_URL=http://kafka-schema-registry:8081
ENV JOARK_HOST=http://joark-mock:8092
ENV FILESTORAGE_HOST=http://soknadsfillager:9042

COPY target/*.jar app.jar

# The purpose of the sleep is to make sure the Kafka Broker has time to start up before we try to connect to it
CMD sleep 30 && java -jar app.jar
