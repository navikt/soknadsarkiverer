FROM navikt/java:11

ENV SPRING_PROFILES_ACTIVE=test
ENV KAFKA_BOOTSTRAP_SERVERS=kafka-broker:29092
ENV SCHEMA_REGISTRY_URL=http://kafka-schema-registry:8081
ENV JOARK_HOST=http://joark-mock:8092
ENV FILESTORAGE_HOST=http://soknadsfillager:9042

ENV EXPIRYTHRESHOLD=2
ENV DISCOVERYURL=https://login.microsoftonline.com/966ac572-f5b7-4bbe-aa88-c76419c0f851/v2.0/.well-known/openid-configuration
ENV ACCEPTEDAUDIENCE=soknadsarkiverer
ENV COOKIENAME=idtoken-cookie

ENV TOKENENDPOINTURL=https://security-token-service.nais.preprod.local/oauth2/v2.0/token
ENV GRANTTYPE=client_credentials
ENV CLIENTID=srvsoknadarkiverer
ENV CLIENTSECRET=noetemmelighemmelig
ENV CLIENTAUTHMETHOD=client_secret_basic

COPY target/*.jar app.jar

CMD java -jar app.jar
