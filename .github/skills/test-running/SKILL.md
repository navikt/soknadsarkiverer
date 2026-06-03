---
name: test-running
description: Documents repo-specific test-running guidance and quirks. Use when running, rerunning, or debugging tests in this repository.
---

# Test Running

## Quick start

When running a specific `arkiverer` test from the repo root, include:

`-Dsurefire.failIfNoSpecifiedTests=false`

Example:

`mvn -q -pl arkiverer -am -DskipITs -Dtest=SchedulerTests -Dsurefire.failIfNoSpecifiedTests=false test`

## Why this matters

With `-pl arkiverer -am`, Maven also builds upstream modules. Some of those modules do not contain the requested test, and Surefire can fail early with:

`No tests matching pattern "... were executed!"`

The `-Dsurefire.failIfNoSpecifiedTests=false` flag prevents that false failure so Maven can continue to the intended `arkiverer` test.

## Workflow

1. If you are running the full test suite, no special handling is needed.
2. If you are running a specific `arkiverer` test from the repo root with reactor flags, add `-Dsurefire.failIfNoSpecifiedTests=false`.
3. Keep any other existing flags, such as `-DskipITs`, unchanged.
