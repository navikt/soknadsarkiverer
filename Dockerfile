FROM gcr.io/distroless/java21-debian12:nonroot

ENV APPLICATION_NAME=soknadsarkiverer
ENV JAVA_TOOL_OPTIONS="-Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2 -XX:+ExitOnOutOfMemoryError"
ENV LANG='nb_NO.UTF-8' LANGUAGE='nb_NO:nb' LC_ALL='nb:NO.UTF-8' TZ="Europe/Oslo"

COPY arkiverer/target/*.jar /app/app.jar

WORKDIR /app

CMD ["app.jar"]
