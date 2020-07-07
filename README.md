# Soknadsarkiverer
This application will read data from a Kafka topic, enrich it by retrieving files associated to the data and then pass the data on to the Joark archive.

For a description of the whole archiving system, see [the documentation](https://github.com/navikt/archiving-infrastructure/wiki).

## Dependencies
This component requires the following to work:
* [soknadsfillager](https://github.com/navikt/soknadsfillager) (REST-endpoint for retrieving files)
* [soknadarkiv-schema](https://github.com/navikt/soknadarkiv-schema) (Avro schema definitions)
* Joark (REST-endpoint for sending data)
* Kafka broker (for providing input data)


## Inquiries
Questions regarding the code or the project can be asked to [team-soknad@nav.no](mailto:team-soknad@nav.no)

### For NAV employees
NAV employees can reach the team by Slack in the channel #teamsoknad
