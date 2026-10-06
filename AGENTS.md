# AGENTS.md

Kafka consumer that enriches applications and archives them in Joark. It fetches submission files from `innsending-api` and uses the shared `soknadarkiv-schema`.

## JVM diagnostics in cplt

On macOS, the sandbox can block `ps` even when JVM attach is allowed. Use `jcmd -l` or `jps -lv` to find JVM process IDs, then `jcmd <PID> Thread.print` or `jstack <PID>` for a thread dump. JVM attach requires `sandbox.allow_jvm_attach` to be enabled in cplt. A blocked `ps` command alone does not mean JVM diagnostics are unavailable; try the JDK tools and report any specific errors.
