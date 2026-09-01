# Avro retirement runbook

This runbook covers the deferred cleanup after the processing-event and metrics
migration described in
[issue #260](https://github.com/navikt/soknadsarkiverer/issues/260). It is the
exit gate for [issue #267](https://github.com/navikt/soknadsarkiverer/issues/267);
it is not permission to remove Avro as part of the initial JSON v3 rollout.

Issues #260 and #267 are the requirements source. They require all retained v2
history to be migrated/replayed and verified, but do not define a migration
program, exact offset procedure, observation period, or retention period. The
operational guardrails below are based on the current branch implementation;
the retirement change must record and approve the concrete values and tooling.

The processing-event log is retained recovery state, not a queue that can merely
be allowed to drain. Do not remove the v2 reader, Schema Registry access, or the
v2 topic until the history has been migrated and the resulting v3-only recovery
state has been verified.

## Current transition state

| Path | Current contract and behavior | Repository references |
| --- | --- | --- |
| Processing events v2 | Avro; read-only during startup replay. Production names are `team-soknad.privat-soknadinnsending-processingeventlog-v2` and the `-dev` variant. | `arkiverer/src/main/kotlin/no/nav/soknad/arkivering/soknadsarkiverer/kafka/bootstrapping/KafkaBootstrapConsumer.kt`, `.nais/config-prod.json`, `.nais/config-preprod.json`, `topicconfig/soknadinnsending-processingEventLog-v2-*.json` |
| Processing events v3 | Plain JSON; the only processing-event write target and the live Kafka Streams input. Startup replay merges it with retained v2 history. | `arkiverer/src/main/kotlin/no/nav/soknad/arkivering/soknadsarkiverer/kafka/KafkaPublisher.kt`, `KafkaStreamsSetup.kt`, `KafkaBootstrapConsumer.kt`, `ProcessingEventJson*.kt`, `topicconfig/soknadinnsending-processingEventLog-v3-*.json` |
| Metrics v2 | Avro; no longer written by this branch, but its binding remains as `KAFKA_METRICS_TOPIC`. There is no metrics-v2 topic manifest under this repository's `topicconfig/`, so ownership and consumers must be located before deletion. | `arkiverer/src/main/resources/application.yml`, `.nais/nais.yml`, `.nais/config-prod.json`, `.nais/config-preprod.json` |
| Metrics v3 | Plain JSON using the generated `soknadsmottaker` OpenAPI model; the only metrics write target in this branch. | `KafkaPublisher.kt`, `InnsendingMetricsJsonMapping.kt`, `InnsendingMetricsJsonSerde.kt`, `topicconfig/soknadinnsending-metrics-v3-*.json` |
| Application submissions | The logged-in and no-login inputs remain JSON strings and are not versioned by this migration. | `KafkaStreamsSetup.kt`, `KafkaBootstrapConsumer.kt`, `arkiverer/src/main/resources/application.yml` |

The production topic bindings are passed through `.nais/nais.yml` as
`KAFKA_PROCESSING_TOPIC`, `KAFKA_PROCESSING_TOPIC_V3`,
`KAFKA_METRICS_TOPIC`, and `KAFKA_METRICS_TOPIC_V3`. Schema Registry settings
are still mandatory application properties through `KAFKA_SCHEMA_REGISTRY`,
`KAFKA_SCHEMA_REGISTRY_USER`, and `KAFKA_SCHEMA_REGISTRY_PASSWORD` in
`arkiverer/src/main/resources/application.yml`.

### Why Avro is still required

Avro and Schema Registry are not isolated to one topic property yet:

- `KafkaBootstrapConsumer.kt` deserializes v2 `ProcessingEvent` records with
  `PoisonSwallowingAvroDeserializer`.
- `KafkaStreamsSetup.kt` still uses generated `ProcessingEvent`/`EventTypes`
  internally and a `SpecificAvroSerde` for join/repartition data, and retains
  Schema Registry settings in the Streams configuration.
- `KafkaPublisher.kt`, `TaskListService.kt`, `ArchiverService.kt`, the JSON
  mapping files, and `ProcessingEventDto.kt` still accept or construct
  generated Avro models before mapping to JSON.
- `pom.xml` and `arkiverer/pom.xml` retain
  `kafka-streams-avro-serde`, the Confluent Maven repository, and the
  `no.nav.soknad.arkivering:avro-schemas` artifact from
  [soknadarkiv-schema](https://github.com/navikt/soknadarkiv-schema).
- `LegacyAvroReplayTest.kt` is the deliberately isolated proof that retained v2
  history can still recover pending work and suppress finished work.
- Shared and older test helpers still contain mocked Schema Registry and
  `SpecificAvroSerializer` defaults, including `ContainerizedKafka.kt`,
  `ApplicationTests.kt`, and `IntegrationTests.kt`; a repository-wide cleanup
  must not stop after deleting the isolated replay test.
- `TranslateUtils.kt` contains an unreferenced
  `Soknadarkivschema`-to-`InnsendingTopicMsg` translation from the older
  application-submission path. Removing it is cleanup work, but the active JSON
  submission topics must remain unchanged.

## Mandatory cleanup prerequisites

All prerequisites apply independently in every environment.

- [ ] The JSON v3 rollout is complete and stable. The intended rollout order is:
  1. publish the expanded `soknadsmottaker` OpenAPI artifact;
  2. deploy the dual-read, v3-writing archiver with the matching
     `archiving-infrastructure` changes;
  3. deploy the v3-only `soknadsmottaker` metrics producer.
  See [issue #265](https://github.com/navikt/soknadsarkiverer/issues/265),
  [soknadsmottaker issue #206](https://github.com/navikt/soknadsmottaker/issues/206),
  and [archiving-infrastructure issue #78](https://github.com/navikt/archiving-infrastructure/issues/78).
- [ ] Every v2 writer has been identified and stopped. Verify stable end offsets,
  not only deployed code. This includes all processing-event writers and all
  metrics-v2 writers, including producers outside this repository.
- [ ] Every v2 consumer and operational use has been identified. In particular,
  find the owner, provisioning source, consumers, and retention obligations for
  metrics-v2 because its topic manifest is not in this repository.
- [ ] A bounded, restartable v2-to-v3 processing-event migration/replay has
  completed, with source end offsets captured per partition before it starts.
  Every retained v2 record must be scanned and its recovery effect represented
  in v3; an event-level history rebuild is additionally required when the
  approved retention requirement calls for raw event history in v3.
- [ ] Every source record up to those end offsets is accounted for as either
  converted successfully or an explicitly accepted poison-record exception.
- [ ] The merged task state reconstructed from v2 plus pre-existing v3 history
  is semantically equal to the state reconstructed from v3 alone after
  migration.
- [ ] A v3-only recovery exercise proves that pending work resumes and terminal
  work is not re-archived.
- [ ] An evidence owner, storage location, retention period, and approval record
  have been agreed. The topic manifests specify partition counts but no
  repository-owned retention period, so do not infer a deletion date from them.
- [ ] A rollback window has passed while the v2 topics, ACLs, Schema Registry
  access, schema artifact, and a known-good dual-read release remain available.
- [ ] The coordinated `archiving-infrastructure` cleanup is ready. Its current
  JSON v3 branch still provisions Schema Registry, legacy topics/configuration,
  and the `avro-schemas` dependency for transition coverage; see
  [archiving-infrastructure PR #79](https://github.com/navikt/archiving-infrastructure/pull/79).

## Migrate retained processing-event history

No production migration command or job exists in this repository. Implement
and review one before following this section; do not use the application
bootstrap consumer as if it copied records, because it only reads and merges
state in memory.

Do not blindly append every old v2 record after the newer v3 history. The live
topology reduces events in arrival order (`ProcessingEventDto.kt`), so appending
an old `RECEIVED` after a newer `STARTED`, for example, can regress the live
state. Issues #260/#267 do not choose between an event-level history rebuild and
a replay that materializes equivalent recovery state. Select and approve one of
the following before implementation:

- **Event-level rebuild:** replay all retained v2 events in source order into an
  empty v3-compatible target, followed by the existing v3 history from the
  controlled writer cutover boundary. Verify it before switching the
  application binding to that target.
- **Recovery-state replay:** process every retained v2 event together with the
  bounded v3 history through the current bootstrap semantics and materialize
  each key's effective state in v3. The evidence approvers must explicitly
  accept that this preserves recovery state rather than a one-for-one raw event
  copy.

Do not use recovery-state replay when an approved audit or retention requirement
requires every raw event to remain represented individually in v3.

1. **Dry-run first.** Build the migration so it can scan and reconcile without
   writing to the production v3 topic. Validate its output in evidence storage
   or a disposable Kafka topic before production publication. Incorrect
   terminal records cannot be removed from an append-only topic.
2. **Freeze the source boundary.** After all v2 processing-event writers are
   stopped, establish an approved controlled pause for v3 processing-event
   writes. Record the v2 and v3 topic identities and the end offset of every
   partition. Keep the pause until the state records are published.
3. **Keep the legacy dependencies available.** The migration reader must have
   the matching generated Avro schemas and Schema Registry access needed to
   decode retained v2 records.
4. **Read through the captured boundaries.** Read every retained v2 record and
   the bounded v3 history required by the selected approach. Decode v2 Avro and
   v3 JSON, preserve the Kafka key, and map only the existing event vocabulary
   (`RECEIVED`, `STARTED`, `ARCHIVED`, `FINISHED`, `FAILURE`).
5. **Produce the approved v3 result.** For an event-level rebuild, preserve the
   phase ordering established by the controlled v2-to-v3 writer cutover. For a
   recovery-state replay, calculate the effective state using the same terminal
   and highest-state behavior as `KafkaBootstrapConsumer.kt`, then write one
   valid JSON state record per relevant key. Define and test restart/checkpoint
   behavior before production use; this repository does not provide
   exactly-once migration guarantees. Keep the existing live topology stopped
   while historical output is published unless consuming it has been explicitly
   tested. Capture target offsets and counts, then start the verified v3-only
   build.
6. **Account for poison records.** The current deserializers deliberately log
   and return `null` for malformed Avro or JSON, after which the record is
   skipped (`KafkaRecordConsumer.kt`). For each skipped source offset, record
   topic, partition, offset, failure category, review decision, approver, and
   remediation or exception reason. Do not copy raw payloads into the evidence
   record unless their handling and storage are approved. Keep the drop
   behavior required by issue #260; a DLQ, automatic retry, durable retry audit,
   or admin retry workflow remains separate work.
7. **Do not migrate metrics merely to satisfy replay.** This application does
   not replay metrics-v2. Preserve or migrate metrics history only when an
   identified consumer, audit requirement, or agreed retention policy requires
   it.

## Verify the migration

Retain the verification evidence, not just a statement that the job completed.

1. Reconcile, for every source partition:

   `records through captured end offset = decoded records + accepted exceptions`

   For recovery-state replay, separately record the number of merged keys and
   emitted v3 state records; state materialization is not expected to preserve
   raw record counts. For an event-level rebuild, reconcile the output record
   count and ordering exceptions as part of the rebuild evidence.

2. Compare task state by key before and after migration. Use the behavior in
   `KafkaBootstrapConsumer.kt`: terminal `FINISHED`/`FAILURE` keys are excluded
   from replay, and unfinished histories are reduced with
   `FINISHED > FAILURE > ARCHIVED > STARTED > RECEIVED`. Compare the merged
   v2+v3 result before cleanup with the v3-only result, not v2 and v3 raw record
   counts.
3. Run the existing `LegacyAvroReplayTest` before removing it. It protects the
   v2-only and cross-topic recovery cases. Run the v3 replay coverage in
   `StateRecreationTests` against representative migrated fixtures, including:
   pending work, finished work, a key whose history crossed the cutover, and an
   accepted malformed-record case.
4. Before the production migration, restart the deployed dual-read version in a
   controlled window. Confirm from bootstrap logs that all expected topic scans
   complete and capture the expected pending/terminal result as the baseline.
5. After materialization, start the build that has the v2 input disabled or
   removed. Confirm that pending work resumes, terminal work creates no new
   archive work, and the result matches the captured dual-read baseline before
   deleting any resource.
6. Confirm v2 end offsets remain unchanged throughout the agreed observation
   window and v3 continues receiving new processing events and metrics.

### Monitoring and consumer lag

This repository defines Prometheus exposure and application metrics, but no
retirement-specific lag dashboard or alert. Establish the queries and owners
before cleanup.

- Monitor v3 Kafka Streams lag and application health/readiness while checking
  archive failures, pending tasks, and unexpected re-archiving. Relevant
  application metrics include `soknadinnsending_gauge_tasks`,
  `soknadinnsending_gauge_tasks_given_up_on`,
  and the Joark success/error counters
  (`arkiverer/src/main/kotlin/no/nav/soknad/arkivering/soknadsarkiverer/supervision/ArchivingMetrics.kt`).
- The live Streams application id is derived as `${kafka.applicationId}_v2` in
  `KafkaSetup.kt` even though `KafkaStreamsSetup.kt` now consumes the v3
  processing topic. Do not classify that consumer as a v2 reader from its group
  name alone.
- Bootstrap group ids contain a fresh UUID on each startup. Their lag is not a
  durable migration-completeness signal. Use captured topic end offsets and the
  migration reconciliation instead.
- Alert or query for renewed writes to both legacy topics during the observation
  window. Any movement invalidates the source boundary and blocks cleanup.
- Record the observation interval and links or exports for the lag, health,
  error, task, and topic-offset evidence used for approval.

## Rollback

Before final deletion, rollback means redeploying the known-good version that
reads both processing-event topics and writes v3, while retaining the v2 topic,
ACL, Schema Registry configuration, schemas, and legacy test.

Do **not** roll back to a v2-only reader after v3 writes have begun: it would
ignore task state that exists only in v3. If migration or v3-only verification
fails before production state records are published, stop cleanup, restore the
dual reader if necessary, resume v3 writers, and correct/re-run the dry-run.

If incorrect state records have already been published to v3, do not assume the
dual reader will neutralize them: terminal or higher-ranked v3 state can
dominate the merge. Use the corrective or clean-topic restoration procedure
that was reviewed before migration; if none was approved, cleanup remains
blocked.

Delete legacy data and Schema Registry subjects last. Once retained history or
required schemas have been deleted, application rollback alone cannot restore
them; restoration then depends on separately verified backups and procedures.
Do not delete a shared Schema Registry service or unrelated subjects.

## Concrete cleanup checklist

Perform these as reviewed changes after all prerequisites are signed off.

### In `soknadsarkiverer`

- [ ] Remove the v2 scan and Avro deserializer from
  `KafkaBootstrapConsumer.kt`; keep and re-run equivalent v3-only recovery
  coverage.
- [ ] Replace generated `ProcessingEvent`/`EventTypes` use in
  `TaskListService.kt`, `KafkaStreamsSetup.kt`, `ProcessingEventDto.kt`,
  `KafkaPublisher.kt`, and bootstrap state reduction with the local JSON/domain
  types. Remove the temporary Avro-to-JSON mapping.
- [ ] Construct the generated OpenAPI JSON metrics model directly in
  `ArchiverService.kt`; remove `InnsendingMetricsJsonMapping.kt` and generated
  Avro `InnsendingMetrics` use.
- [ ] Remove `SpecificAvroSerde`, `SpecificAvroSerializer`,
  `SpecificAvroDeserializer`, and all Schema Registry configuration from
  `KafkaStreamsSetup.kt`, `KafkaPublisher.kt`, and `KafkaRecordConsumer.kt`.
  Ensure repartition/join serdes are explicitly non-Avro.
- [ ] Remove `SchemaRegistry` from `KafkaConfig.kt` and remove
  `KAFKA_SCHEMA_REGISTRY*` properties from application and test profiles once
  no remaining path uses them.
- [ ] Remove `processingTopic`, `metricsTopic`,
  `KAFKA_PROCESSING_TOPIC`, and `KAFKA_METRICS_TOPIC` bindings from
  `application.yml`, `.nais/nais.yml`, and all `.nais/config-*.json` files.
  Keep the v3 bindings (or rename them only in a separately planned,
  backward-compatible configuration change).
- [ ] Remove the v2 processing-event topic manifests/ACLs under `topicconfig/`.
  Locate and coordinate the externally managed metrics-v2 manifest/ACL before
  deleting it.
- [ ] Remove `kafka-streams-avro-serde`, `arkivering-schemas.version`,
  `avro-schemas`, and the Confluent Maven repository from the Maven files after
  confirming no generated type remains.
- [ ] Remove the unreferenced Avro application-submission translation in
  `TranslateUtils.kt` and its `Soknadarkivschema`/`Soknadstyper` imports.
  Do not change the active JSON submission contracts or topic versions.
- [ ] Delete `LegacyAvroReplayTest.kt` only after its v3-only replacement proves
  the same recovery behavior. Remove mocked Schema Registry and Avro producer
  setup from shared test utilities and profiles.
- [ ] Update comments, `SelfDestructConfig.kt`, and `README.md` so they no
  longer describe Avro or v2 as active dependencies.
- [ ] Search the complete repository for `avro`, `SpecificAvro`,
  `SchemaRegistry`, `SCHEMA_REGISTRY`, `avro-schemas`,
  `soknadarkiv-schema`, `processingTopic`, `metricsTopic`,
  `KAFKA_PROCESSING_TOPIC`, and `KAFKA_METRICS_TOPIC`; review every remaining
  match rather than assuming it is harmless.

### Coordinated dependencies and resource retirement

- [ ] Update `archiving-infrastructure` to remove the legacy v2 topic setup,
  Schema Registry container/environment/dependencies, Avro listeners/models,
  and `avro-schemas` dependency only after its default v3 system tests pass
  without them.
- [ ] Confirm no other repository still consumes the
  `soknadarkiv-schema` artifact. Archive or delete the artifact/repository only
  under its owners' process and only after all consumers have released.
- [ ] Remove v2 ACLs and bindings before deleting topics, then verify the
  applications still start and v3 lag remains healthy.
- [ ] Retain topic data and relevant Schema Registry subjects for the approved
  evidence/rollback period. Delete them only after the final approval record is
  complete.
- [ ] Run the focused v3 replay tests, the normal repository test suite, and the
  coordinated end-to-end suite. Store links to the exact commits and successful
  runs in the retirement evidence.

## Evidence record

At minimum, the retirement approval should identify:

- environment, topic names, partitions, captured source end offsets, and
  capture time;
- deployed producer and consumer versions proving v2 writes have stopped;
- migration implementation/version, run ids, converted counts, and target
  offsets;
- poison-record exception list and approvals;
- per-key semantic reconciliation result;
- v3-only restart/recovery test result;
- monitoring/lag evidence and observation interval;
- retention and rollback deadline;
- approvals from the application, Kafka/topic, schema artifact, and
  `archiving-infrastructure` owners.
