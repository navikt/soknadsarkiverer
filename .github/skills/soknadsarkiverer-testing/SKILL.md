---
name: soknadsarkiverer-testing
description: Write, change, run and review tests in soknadsarkiverer. Use when adding or updating Kotlin tests, running or debugging the test suite, or reviewing changes that include tests, especially the Kafka archiving flow, state recreation on startup, retries and the admin API.
---

# Testing soknadsarkiverer

soknadsarkiverer is a Kafka consumer. It reads applications from the submission topics, fetches files from innsending-api, checks SAF, creates a journalpost in Joark and publishes processing events, messages and feedback to Kafka. Tests should prove that chain, not the internals of each step.

Use these principles when writing tests and when reviewing changes that include tests. Read the changed behaviour and the nearby tests first. Existing tests provide fixtures and infrastructure. They do not exempt new tests from these principles, and several existing tests break them.

Keep tests focused on the main flows and selected edge cases. Prioritise archiving correctness, retries and recovery on startup over low-signal tests that are unlikely to catch a real defect. Do not add a test that repeats a scenario another test already covers at the same boundary.

## Choose the test boundary

Choose the test type by the behaviour you verify. A rule that can be tested without Kafka or Spring belongs in a unit test, even if the production code currently calls it from a Kafka consumer. Use Kafka tests for what needs a broker: topic routing, serialization, offsets and the order of events.

The naming in the table applies to new test classes. Existing class names vary, and most end in `Tests`.

| Test type | Choose when | Boundary | Example |
| --- | --- | --- | --- |
| Unit test | Conversion to a journalpost request, serde, filtering, the replay rules, the record consumer's stop logic | Call the code directly without Spring, Kafka or WireMock. Use `<Subject>Test`. | `MessageConverterTests`, `KafkaRecordConsumerTests` |
| Service unit test | `TaskListService` or `ArchiverService` behaviour, such as state transitions and retry counting | Construct the service with MockK dependencies. Run scheduled tasks synchronously by capturing them from a mocked `Scheduler`. Use `<Service>UnitTest`. | `TaskListServiceTests` |
| Archiving flow test | An application on a submission topic leads to calls to innsending-api, SAF and Joark, and to events on the output topics | Shared Spring context, real Kafka from `KafkaTestContainerConfiguration`, WireMock for the HTTP services. Use `<Feature>FlowTest`. | `ApplicationTests` |
| Replay test | What `KafkaBootstrapConsumer.recreateState()` reads from real topics on startup | Real Kafka, `TaskListService` mocked or a test subclass. Use `<Feature>ReplayTest`. | `StateRecreationTests`, `LegacyAvroReplayTest` |
| Admin API test | Endpoints under `/admin` and their authentication | HTTP through `WebTestClient` and the real security filter chain. Use `<Api>RestApiTest`. | `ApplicationAdminTest` |
| Lifecycle test | Startup, readiness and the order in which Kafka clients and the broker close | The smallest Spring context that shows the behaviour. Use `<Subject>Test`. | `KafkaTeardownTests`, `KafkaSetupTests` |

Default tests use the JSON v3 processing-event contract. Keep Avro producers and the mocked Schema Registry confined to `LegacyAvroReplayTest`.

## Assert on observable boundaries

In flow and replay tests, verify what other systems can see:

- requests to Joark, SAF and innsending-api recorded by WireMock, including the Joark request body
- processing events on `processingTopicV3`
- messages, arkiveringstilbakemeldinger and metrics on their topics, read through `utils/KafkaListener.kt`
- calls to `TaskListService.addOrUpdateTask` or `ArchiverService.archive` in replay tests, where those are the boundary

Filter every observation on this test's `innsendingsId`. Other tests in the same context publish to the same topics.

Derive the expected outcome from the behaviour contract. Do not compute it with the production helper under test, from a configured mock's return value or from the current value of a global metric. Assert `ArchivingMetrics` only when the metric itself is the behaviour you change, and compare it within one test rather than across tests.

Do not spy on internal orchestration to prove an outcome that an external boundary shows. A spy is acceptable when the test needs to inject a failure that WireMock cannot produce, such as a failing Kafka publish. Name that reason in the test.

## Wait for an outcome, not for time

Archiving runs on scheduled tasks and fire-and-forget coroutines, so most effects arrive after the test's action returns.

- Do not use `Thread.sleep`, `TimeUnit.sleep` or `delay` to wait for an effect. Wait with a bound for an observable state, then assert.
- In flow tests, wait until this key has reached its terminal processing event, FINISHED or FAILURE. Then assert exact counts. Asserting a count while events are still arriving proves only that the count was reached once.
- A negative assertion such as "no request to Joark" or "FAILURE count 0" is only meaningful after the work it covers has finished. Assert it after the terminal event for the same key, or after a later valid message on the same topic has finished. `utils.loopAndVerify` returns at once when the expected count is 0, so it cannot prove absence on its own.
- Do not rely on MockK `verify(exactly = n, timeout = ...)` to prove an exact count. It returns as soon as the count is reached, so later calls go unnoticed, and with `exactly = 0` it returns at once. Use a `CountDownLatch` that the mock counts down, as `StateRecreationTests.archiveLatch` does, or wait for the terminal event and then verify the exact count without a timeout.
- Await every coroutine the test starts, with `runBlocking` or `runTest`. An assertion inside `CoroutineScope(...).launch { ... }` that nobody awaits never reaches JUnit, and the test passes whatever happens.
- Keep bounds short and explicit. A bound protects against a hang. It is not a way to let slow work finish.
- When timing is the behaviour under test, inject a clock, as `KafkaRecordConsumerTests` does with `TestClock`, instead of sleeping.

## Keep tests isolated

- Each distinct set of `@MockitoBean`, `@MockitoSpyBean`, `@MockkBean`, properties or `webEnvironment` starts a new Spring context and a new Kafka container. Reuse the configuration of an existing test class where you can. Explain any new combination in the PR.
- `ContainerizedKafka` marks the context dirty after each class. Do not add `@DirtiesContext` to individual tests unless the test closes the context on purpose, as `KafkaTeardownTests` does.
- Kafka Streams does not create its source topics. Add a new topic to `KafkaTestContainerConfiguration` so it exists before Spring starts.
- `ArchivingMetrics` registers in `PrometheusRegistry.defaultRegistry`, which all tests in the JVM share. Unregister in `@AfterEach`, as the existing classes do, and do not let assertions depend on what earlier tests left behind.
- Close every Kafka producer, consumer and listener the test creates, with a bounded timeout. A client that outlives its broker keeps reconnecting for the rest of the run.
- Clear state the test leaves in shared beans, such as `TaskListService.clearLoggedTaskStates()` and recorded mock calls.
- Use synthetic data only. The builders in `utils/Builders.kt` use synthetic identity numbers. Do not print personal data, tokens or secrets in test output or failure messages.

## Reuse the repository's tools

Paths are relative to `arkiverer/src/test/kotlin/no/nav/soknad/arkivering/soknadsarkiverer/`.

- `utils/Builders.kt` builds `InnsendingTopicMsg` with `InnsendingTopicMsgBuilder` and `TestDokument`. Start there for new applications.
- `utils/WiremockSetup.kt` stubs Joark, SAF and innsending-api for the common success, failure and retry scenarios, and counts recorded requests.
- `utils/KafkaListener.kt` collects records from the output topics.
- `utils/ContainerizedKafka.kt` and `utils/KafkaTestContainerConfiguration.kt` own the Kafka broker for a Spring test context.
- `utils/TokenGenerator.kt` issues tokens from `MockOAuth2Server` for admin API tests.
- `ApplicationTests.verifyRequestDataToJoark` shows how to check the whole Joark request against the input message.

Extend these helpers when a new test needs something they lack. Do not copy helper functions into the test class.

## Check that tests detect defects

Avoid tautological tests. For each new or changed test, make a temporary mutation in the production code, such as reversing a condition or removing a publish, and run the focused test. It should fail. Restore the mutation immediately, even if the run fails, then check the diff and rerun the test. Investigate a surviving mutation: either the test lacks a behavioural assertion, or the mutation does not change behaviour. Do not add assertions on implementation details only to kill a mutation.

## Run tests

Run from the repository root. Run `mise install` once, and use the Java and Maven versions in `mise.toml` through `mise exec`.

The root reactor contains `clients`, `arkiverer`, `admin-api` and `external-api`. The tests live in `arkiverer`. With `-pl arkiverer -am`, Maven also builds the modules `arkiverer` depends on. Those modules do not contain the selected test, and Surefire fails with `No tests matching pattern` unless you add `-Dsurefire.failIfNoSpecifiedTests=false`.

```sh
# One unit test class
mise exec -- mvn -pl arkiverer -am test -Dtest=MessageConverterTests -Dsurefire.failIfNoSpecifiedTests=false

# One test method. Quote the full name, or match part of it with *
mise exec -- mvn -pl arkiverer -am test -Dtest='ApplicationTests#Failing*Joark*retries' -Dsurefire.failIfNoSpecifiedTests=false

# Several classes
mise exec -- mvn -pl arkiverer -am test -Dtest=StateRecreationTests,LegacyAvroReplayTest -Dsurefire.failIfNoSpecifiedTests=false

# The full build that CI runs
mise exec -- mvn clean install
```

Tests that extend `ContainerizedKafka` start `apache/kafka-native` through Testcontainers and need a running Docker-compatible runtime. If the Docker socket is unavailable in a sandbox, ask the user to start Docker or Colima outside the sandbox. The native image fails with GraalVM error 24 on ARM64 hosts with 16 KiB pages, so run those tests on a supported host.

Check `arkiverer/target/surefire-reports/` to confirm that the intended tests ran. The same files give the time per class and per test. Each test class with its own Spring context adds its startup and shutdown to the total, so look at the class time as well as the test times.

## Report

When a change affects how long the tests take, report the time before and after in the PR, taken from Surefire reports or the CI log. Report the commands you ran, failures, and tests you did not run. When reviewing, distinguish a missing behavioural assertion from a stylistic preference, and name untested outcomes without widening the change into unrelated test cleanup.
