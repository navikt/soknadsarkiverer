FROM azul/zulu-openjdk-alpine:12

COPY target/*SNAPSHOT.jar /app.jar

# The purpose of the sleep is to make sure the Kafka Broker has time to start up before we try to connect to it
CMD sleep 30 && java -jar /app.jar
