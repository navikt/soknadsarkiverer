FROM navikt/java:11

ENV JAVA_OPTS="-Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2 -XX:+ExitOnOutOfMemoryError"

COPY target/*.jar app.jar
COPY init-scripts /init-scripts

CMD java -jar app.jar
