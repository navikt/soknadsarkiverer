# Soknadsarkiverer
This application will read data from a Kafka topic, enrich it by retrieving files associated to the data and then pass the data on to the Joark archive.

For a description of the whole archiving system, see [the documentation](https://github.com/navikt/archiving-infrastructure/wiki).

Operational documentation:
* [Avro retirement runbook](docs/avro-retirement-runbook.md)

## Local dependencies and tests

Run `docker compose up -d` to start the local OAuth server on port 6969 and
Kafka on port 29092. Kafka uses a single combined KRaft broker/controller,
without ZooKeeper. This setup is for development, not production. Existing
ZooKeeper broker data must not be reused with this configuration.

Run `mise install` and `mise exec -- mvn clean install` for the complete build
and test suite. Application tests start a real native Kafka container and
create their source topics before Spring starts. The upstream native image
requires a compatible host page size. On ARM64 hosts with 16 KiB pages it
fails with GraalVM error 24, so run these tests on a supported host.

## Dependencies
This component requires the following to work:
* [innsending-api](https://github.com/navikt/innsending-api) (REST-endpoint for retrieving files)
* [soknadarkiv-schema](https://github.com/navikt/soknadarkiv-schema) (Avro schema definitions)
* Joark (REST-endpoint for sending data)
* Kafka broker (for providing input data)

## API Users
### Production
These groups have API access in production:
* [nais-team-fyllut-sendinn](https://myaccount.microsoft.com/groups/5c4d5ed5-eeb8-4586-bf2f-3421ee98df40) (5c4d5ed5-eeb8-4586-bf2f-3421ee98df40)
* [Team Skjemadigitalisering](https://myaccount.microsoft.com/groups/6d98053f-362d-447e-a272-8132f278f42b) (6d98053f-362d-447e-a272-8132f278f42b)

### Preprod
Preprod requires an NAV preprod user

## Inquiries
Questions regarding the code or the project can be asked to the team by [raising an issue on the repo](https://github.com/navikt/soknadsarkiverer/issues).

### For NAV employees
NAV employees can reach the team by Slack in the channel #teamsoknad
