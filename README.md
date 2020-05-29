# Soknadsarkiverer
This application will read data from a Kafka topic, enrich it by retrieving files associated to the data and then pass the data on to the Joark archive.

For a description of the whole archiving system, see [the documentation](https://github.com/navikt/archiving-infrastructure/wiki).

## Dependencies
This component requires the following to work:
* [soknadsfillager](https://github.com/navikt/soknadsfillager) (REST-endpoint for retrieving files)
* [soknadarkiv-schema](https://github.com/navikt/soknadarkiv-schema) (Avro schema definitions)
* Joark (REST-endpoint for sending data)
* Kafka broker (for providing input data)

## Setup
The Kafka topic transports Avro serialized events defined in [soknadarkiv-schema](https://github.com/navikt/soknadarkiv-schema). The Avro schema definitions are made available publicly on the GitHub Package Registry.

Unfortunately, [GitHub doesn't currently allow accessing its package registry without authorization](https://github.community/t5/GitHub-API-Development-and/Download-from-Github-Package-Registry-without-authentication/m-p/35501#M3312).
Until that has been fixed, one must have a [Personal Github access token](https://github.com/settings/tokens) with SSO enabled and `read:packages=true`.

### Building from command line
* In order to build like normal, add the relevant parts of ./.m2/maven-settings.xml to your ~/.m2/settings.xml file and set the environment variables `GITHUB_USERNAME` and `GITHUB_TOKEN` (e.g. in ~/.bashrc). Then it is possible to build with `mvn clean install` like normal.

* In order to build just this component without changing ~/.m2/settings.xml, one can run the following instead of the step above:<br />
`export GITHUB_USERNAME=<INSERT_GITHUB_USERNAME_HERE> && export GITHUB_TOKEN=<INSERT_GITHUB_ACCESS_TOKEN_HERE> && mvn --settings ./.m2/maven-settings.xml clean install`

### Building from IntelliJ
* Settings -> Build, Execution, Deployment -> Build Tools -> Maven -> Runner<br />
Add this to the Environment Variables:<br />
`GITHUB_TOKEN=<INSERT_GITHUB_ACCESS_TOKEN_HERE>;GITHUB_USERNAME=<INSERT_GITHUB_USERNAME_HERE>`

* If you have not merged ./m2/maven-settings.xml to ~/.m2/settings.xml as per above, you also need to do the following:<br />
Settings -> Build, Execution, Deployment -> Build Tools -> Maven<br />
Override User Settings File and specify ./m2/maven-settings.xml

## Kafka stream configuration
This application consumes messages on the stream privat-soknadInnsendt-v1* (where * is, depending on environment, is replaced with none | -default | -q0 | q1 ).
This stream is configured using the stream configuration found in kafka-config.json in the soknadsmottaker project, see https://github.com/navikt/soknadsmottaker/blob/master/kafka-config.json. Replace TODO with correct application user names for producer and consumer roles and run
After specifying producer and consumer, use the result to execute the "one-shot" option found in https://kafka-adminrest.nais.preprod.local/api/v1/.
The application uses the stream privat-soknadInnsendt-processingEventLog-v1* (replace * depending on environment as described above) as both producer and consumer in order to process messages read from privat-soknadInnsendt-v1.
This stream is configures using the configuration kafka-config.json found in this project. Replace TODO with username for this application and use the "one-shot" option found in https://kafka-adminrest.nais.preprod.local/api/v1/.

## Inquiries
Questions regarding the code or the project can be asked to [team-soknad@nav.no](mailto:team-soknad@nav.no)

### For NAV employees
NAV employees can reach the team by Slack in the channel #teamsoknad
