# Soknadsarkiverer
This application will read data from a Kafka topic, enrich it by retrieving files associated to the data and then pass the data on to the Joark archive.

For a description of the whole archiving system, see [the documentation](https://github.com/navikt/archiving-infrastructure/wiki).

Operational documentation:
* [Avro retirement runbook](docs/avro-retirement-runbook.md)

## Shutdown
`/internal/stop` stops admission of new coroutine work and waits for in-flight file retrieval,
archiving, feedback, and final processing-event publication. Spring context shutdown also drains
the jobs owned by `TaskListService` before closing their Kafka publisher. Scheduled tasks that
have not started are cancelled; unfinished applications are recovered by Kafka replay.

Task draining waits for the actual work to finish, not just the scheduler callbacks that launched
it. The Kafka client close timeouts do not bound this drain. Kubernetes can still terminate the
pod when its termination grace period expires, so the grace period must accommodate in-flight
requests as well as client cleanup.

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
