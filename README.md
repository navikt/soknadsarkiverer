# Soknadsarkiverer
This application will read data from a Kafka topic, enrich it by retrieving files associated to the data and then pass the data on to the Joark archive.

For a description of the whole archiving system, see [the documentation](https://github.com/navikt/archiving-infrastructure/wiki).

## Dependencies
This component requires the following to work:
* [soknadsfillager](https://github.com/navikt/soknadsfillager) (REST-endpoint for retrieving files)
* [soknadarkiv-schema](https://github.com/navikt/soknadarkiv-schema) (Avro schema definitions)
* Joark (REST-endpoint for sending data)
* Kafka broker (for providing input data)


## Admin Rest-API
This application is driven by consuming events on Kafka streams. There is, however, an Admin Rest interface (intended to be used by [soknadsadmins](https://www.github.com/navikt/soknadsadmins)):

* [localhost](http://localhost:8091/swagger-ui/index.html?configUrl=/v3/api-docs/swagger-config)
* [q0](https://soknadsarkiverer-q0.dev.intern.nav.no/swagger-ui/index.html?configUrl=/v3/api-docs/swagger-config)
* [q1](https://soknadsarkiverer-q1.dev.intern.nav.no/swagger-ui/index.html?configUrl=/v3/api-docs/swagger-config)
* [teamsoknad (dev-fss)](https://soknadsarkiverer.dev.intern.nav.no/swagger-ui/index.html?configUrl=/v3/api-docs/swagger-config)
* [prod](https://soknadsarkiverer.intern.nav.no/swagger-ui/index.html?configUrl=/v3/api-docs/swagger-config)

## Inquiries
Questions regarding the code or the project can be asked to the team by [raising an issue on the repo](https://github.com/navikt/soknadsarkiverer/issues).

### For NAV employees
NAV employees can reach the team by Slack in the channel #teamsoknad
