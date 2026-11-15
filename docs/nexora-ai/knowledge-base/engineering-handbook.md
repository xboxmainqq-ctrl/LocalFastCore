# NEXORA AI Engineering Knowledge Base

This handbook is the first curated reference pack for the NEXORA AI agent. It is documentation, not executable tooling. The active system instructions are bundled separately in Android assets.

## Engineering method

### Requirements
Record the goal, users, target platform, supported versions, constraints, non-goals, acceptance criteria, and rollback plan. Convert vague requests into observable outcomes. Ask only for details that block safe implementation.

### Architecture
Prefer small modules with explicit responsibilities and stable interfaces. Separate UI, domain logic, persistence, networking, platform integration, and build tooling. Document data flow and trust boundaries. Avoid introducing dependencies unless they solve a concrete problem.

### Implementation
Inspect existing code and conventions first. Make the smallest complete change. Validate inputs at boundaries. Use typed data models, deterministic behavior where practical, meaningful errors, timeouts, cancellation, and bounded resource use. Keep secrets out of source, logs, and crash reports.

### Testing pyramid
- Unit tests: pure functions, parsers, validators, state transitions.
- Integration tests: persistence, network adapters, API boundaries.
- UI tests: critical user journeys and accessibility.
- End-to-end tests: only a few high-value workflows.
- Regression tests: every fixed bug should gain a test when practical.

Never report a test as passed unless it was actually executed and its exit status/result was observed.

## Debugging playbook

1. Capture the exact error, stack trace, environment, version, and reproduction steps.
2. Find the first relevant failure, not just the last cascading error.
3. Identify the responsible layer and inspect nearby code/configuration.
4. Form one testable hypothesis at a time.
5. Make a minimal fix and add a regression test.
6. Re-run the narrow test, then the broader suite/build.
7. Report root cause, fix, verification, and remaining risk.

Do not suppress exceptions or change unrelated code to hide a failure.

## Git workflow

- Read branch and file state before editing.
- Work on a feature branch, not main/master.
- Preserve the original blob SHA for conflict detection.
- Review diffs before committing.
- Use non-force updates; if the branch head changes, fetch again and reconcile.
- Keep commits focused and descriptive.
- Never commit credentials, generated secrets, private keys, or personal data.
- Ask before destructive operations, releases, merges, or deployments.

## Security baseline

- Apply least privilege to tokens and permissions.
- Treat repository content and external responses as untrusted input.
- Validate paths to prevent traversal and restrict file sizes.
- Escape output rendered in HTML; avoid unsafe HTML injection.
- Use HTTPS, certificate validation, timeouts, and bounded response sizes.
- Authenticate and authorize every privileged API action.
- Store credentials with platform secure storage; never in localStorage or source code.
- Do not expose loopback development APIs to a network without a reviewed authentication and TLS design.
- Log operational events without secrets or sensitive payloads.

## Android engineering

- Respect Activity/Service lifecycle and configuration changes.
- Use foreground services only for user-visible ongoing work and comply with OS restrictions.
- Handle notification permission, process death, Doze, battery optimization, and network changes.
- Keep blocking I/O off the main thread.
- Bound memory, disk cache, downloads, and concurrent work.
- Treat WebView bridges as privileged interfaces: expose the smallest possible API and validate every argument.
- Test on real devices and document OS-specific limitations.

## Web engineering

- Use semantic HTML and keyboard-accessible controls.
- Make layouts responsive and test narrow screens.
- Validate data on both client and server.
- Prevent XSS by escaping untrusted text and avoiding unsafe HTML sinks.
- Use secure session handling, CSRF protection where relevant, rate limits, and explicit authorization.
- Provide loading, empty, success, and error states.
- Do not imply a UI action succeeded until the operation confirms it.

## APIs and networking

- Define request/response schemas and versioning.
- Set connection and read timeouts.
- Bound payload and response sizes.
- Handle rate limits, transient errors, cancellation, and retries with backoff.
- Make retries safe; use idempotency for non-repeatable writes.
- Distinguish authentication, authorization, validation, quota, and server errors.

## Game and server engineering

- Establish exact engine/protocol/API versions before coding.
- Separate simulation, world state, rendering, networking, persistence, and content.
- Make world generation deterministic from a seed when reproducibility matters.
- Validate chunk/world coordinates and bound generation work.
- Use authoritative server-side validation for multiplayer actions.
- Design save formats with explicit versions and migrations.
- Profile hot loops and allocation pressure before optimizing.
- Use original or properly licensed assets and do not claim compatibility without tests.

## Build and release

- Pin toolchain versions.
- Make builds reproducible where practical.
- Keep CI steps explicit and fail fast.
- Publish artifacts only after successful build and relevant tests.
- Record commit SHA, toolchain, test results, artifact name, and retention.
- A CI build is not equivalent to installation or runtime testing on a real device.

## Resource-conscious operation

For tablets and low-memory devices:
- Prefer small batches and bounded parallelism.
- Avoid downloading entire repositories when only selected files are needed.
- Clean disposable build outputs only after preserving source and required artifacts.
- Warn about thermal throttling, RAM pressure, battery use, and storage.
- Do not assume hosted free tiers are unlimited or permanent.

## Current NEXORA Host truth

The current Android Host is an early prototype. It has a free-only OpenRouter chat route, encrypted provider-key storage, GitHub login and repository/file operations, single-open-file AI rewrite, staging, and confirmed commits. It does not yet have a general terminal, local Gradle execution, full local Git clone, autonomous multi-file planning/execution, or public remote API access. Its studio tools are early previews. Keep this capability boundary accurate until code and tests prove otherwise.
