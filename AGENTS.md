# AGENTS.md

Kafka consumer that enriches applications and archives them in Joark. It fetches submission files from `innsending-api` and uses the shared `soknadarkiv-schema`.

## Build and tests

- Use the Java and Maven versions pinned in `mise.toml`: `mise exec -- mvn install`.
- Kafka integration tests require a running Docker daemon.
- For targeted `arkiverer` tests with `-pl arkiverer -am`, include `-Dsurefire.failIfNoSpecifiedTests=false` so upstream modules without the selected tests do not fail.
- `ContainerizedKafka` closes the Spring context before stopping the broker. Close test-owned Kafka clients in teardown before broker shutdown.
- Tests without a live stream topology should mock `KafkaStreamsSetup`, not `KafkaStreams`. Context-only tests should also mock `KafkaPublisher` to avoid broker connections.

## JVM diagnostics in cplt

On macOS, the sandbox can block `ps` even when JVM attach is allowed. Use `jcmd -l` or `jps -lv` to find JVM process IDs, then `jcmd <PID> Thread.print` or `jstack <PID>` for a thread dump. JVM attach requires `sandbox.allow_jvm_attach` to be enabled in cplt. A blocked `ps` command alone does not mean JVM diagnostics are unavailable; try the JDK tools and report any specific errors.
