FROM navikt/java:17

ENV APPLICATION_NAME=soknadsarkiverer
ENV JAVA_OPTS="-Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2 -XX:+ExitOnOutOfMemoryError"
COPY arkiverer/target/*.jar app.jar

CMD java -jar app.jar
COPY init-scripts /init-scripts
