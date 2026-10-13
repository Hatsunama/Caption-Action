# Synthetic capture build candidate

Isolated unsigned diagnostic, package `com.hatsunama.captionaction.captureprobe`.
This source is not the production Caption application. It contains synthetic tone
generation and own-UID capture code, no model weights, network permission, user
audio, recordings, credentials, or private device logs.

CI compiles only this directory with JDK 17, Gradle 8.13, Kotlin 1.9.24, AGP 8.10.1
and SDK 36. Required checks: 25 Gradle JUnit tests, 32 test-only JVM tests (including
7 lifecycle tests using verbatim service slices), 9 structural checks, full debug
lint, and unsigned debug assembly. Platform stand-ins are not device proof.

No signing, installation, device interaction, emulator, runtime capture, or
production application build is part of this workflow. Passing CI does not
authorize any of those steps. All Android runtime permissions and consent remain
required for any separately authorized future experiment.

The candidate preserves CoroutineStart.ATOMIC and an immediate ensureActive()
before capture setup, so cancellation before initial dispatch still reaches the
existing cleanup. Production source SHA-256 is recorded in SOURCE_SHA256SUMS.
