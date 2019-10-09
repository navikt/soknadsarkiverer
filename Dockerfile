FROM azul/zulu-openjdk-alpine:12

COPY target/*SNAPSHOT.jar /app.jar

CMD java -jar /app.jar
