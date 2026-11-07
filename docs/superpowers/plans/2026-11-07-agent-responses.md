# Agent Responses API Compatibility Plan

> **For agentic workers:** Use superpowers:executing-plans with TDD. The user requested the fix after the diagnosis; implement and verify it in this checkout.

**Goal:** Allow GPT-6 agents to use tools and reasoning through the Responses API.

**Architecture:** Select Koog's Responses endpoint for GPT-6 model identifiers and retain Chat Completions for other compatible providers. Convert the configured reasoning effort to the Responses `reasoning.effort` format, retaining other reasoning options and the agent's ownership of conversation/tool fields.

**Tech Stack:** Kotlin Multiplatform, Koog 1.3.0-SNAPSHOT, Ktor MockEngine, Gradle.

**Spec:** The screenshot rejects GPT-6 Astra tools with reasoning on Chat Completions. The user requested the diagnosed code fix. Official documentation requires Responses for Astra tool calls and does not support `none` reasoning for Astra.

## Global Constraints

- Preserve existing provider settings, user data and non-GPT-6 Chat Completions behavior.
- Use existing Koog support without changing dependency versions.
- Use local simulated HTTP responses for testing; no account credentials or live model requests.
- Leave changes reviewable in the current checkout; no publishing, installation or push was requested.

## Review Focus

- GPT-6 model snapshots and provider-prefixed identifiers choose Responses.
- Tools execute and their results and opaque reasoning return in the next request.
- Default effort is omitted; explicit effort overrides extra-body effort without losing other reasoning settings.
- Extra request fields cannot overwrite the agent's input, tool definitions or conversation history.
- Existing models and providers retain their Chat Completions behavior.

## Task 1: Fix the Agent protocol boundary

**Files:** `AiConfigKoogBridge.kt` and a small Responses HTTP factory adapter under the Agent runtime package; `AgentChatHistoryProvider.kt`; regression tests in `AgentAiConfigurationTest.kt` and `AgentChatHistoryProviderTest.kt`.

- [x] Add failing tests that execute the real agent against simulated Responses tool calls and inspect serialized requests, reasoning and history.
- [x] Run the focused JVM tests and confirm failure is due to the current Chat Completions route.
- [x] Select Responses for GPT-6 identifiers; translate effort and protect conversation fields.
- [x] Run the focused tests, then the Agent JVM suite and relevant compilation/style checks.
- [x] Review the final diff and report the verified scope and any build or runtime limitations.

## Execution Notes

- Initial offline baseline could not resolve uncached multiplatform dependencies. Retrying with the existing local Gradle repository filter and network dependency resolution.
- The system Java launcher has no registered runtime; use Android Studio's bundled JDK for Gradle.
- Cached Koog already supports Responses but needs the Thinking capability to replay reasoning. Its older response metadata effort enum omits `xhigh`/`max`; scope compatible JSON decoding to the non-streaming Responses client while preserving the requested wire effort.
- History replay currently strips all reasoning. Preserve opaque reasoning associated with complete tool exchanges while retaining raw-reasoning and orphan-tool sanitization.
- Three initial regression tests failed for the expected endpoint/history reasons, then all 30 focused tests passed.
- Review found that Koog maps a successful Responses message to finish reason `COMPLETED`. A real Responses compaction test reproduced the rejection; accept this status while retaining incomplete-summary rejection.
- Scope new reserved fields to Responses, preserving compatible-provider Chat Completions extras. The existing boundary test reproduced lost custom fields before this adjustment.
- Scope format/check tasks to commonMain, commonTest and jvmTest; the aggregate formatter unnecessarily triggers unrelated native builds. No application package or installation is part of validation.

## Final Verification

- All 169 Agent JVM tests passed, with zero failures, errors or skips.
- Ktlint checks passed for commonMain, commonTest and jvmTest; `git diff --check` passed.
- Android Agent compilation and `:app:compileDebugKotlin` passed. Gradle reported `BUILD SUCCESSFUL` (184 tasks, 14 executed).
- Independent final review reported no remaining findings after the compaction and passthrough corrections.
- Tests used simulated HTTP responses and local SQLite persistence. No real model API request, application packaging, device installation, commit or push was performed.
