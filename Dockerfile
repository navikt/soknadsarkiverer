FROM navikt/java:11

ENV SPRING_PROFILES_ACTIVE=test
ENV KAFKA_BOOTSTRAP_SERVERS=kafka-broker:29092
ENV SCHEMA_REGISTRY_URL=http://kafka-schema-registry:8081
ENV JOARK_HOST=http://joark-mock:8092
ENV FILESTORAGE_HOST=http://soknadsfillager:9042

COPY init.sh /init-scripts/init.sh
RUN chmod +x /init-scripts/init.sh
CMD ./init-scripts/init.sh

COPY target/*.jar app.jar

CMD java -jar app.jar
