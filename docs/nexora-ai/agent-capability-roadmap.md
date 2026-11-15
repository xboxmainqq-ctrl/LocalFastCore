# NEXORA AI Agent Capability Roadmap

## Guiding rule
Increase capability through verified tools and tested workflows, not by pretending that a system prompt alone grants new abilities. Every capability must have a clear permission boundary, visible status, audit trail, and failure mode.

## Stage 1 — Core engineering behavior (started)
- Persistent system instructions loaded from Android assets.
- Engineering workflow, debugging discipline, security rules, Git safety, and truthful capability boundaries.
- Curated engineering handbook.
- CI build verification for every change.

## Stage 2 — Project understanding
- Workspace manifest with project type, toolchain, entry points, build/test commands, and file map.
- Bounded repository tree traversal and selective file reads.
- Per-file base blob SHA and branch-head snapshot.
- Project context summary refreshed after file changes.
- Ignore secrets, binaries, generated files, and oversized content by default.

## Stage 3 — Multi-file coding agent
- Structured task plan with explicit file operations: create, update, delete.
- Model output schema with strict JSON parsing and validation.
- Reviewable unified diffs and per-file previews.
- Staging across multiple files with conflict detection.
- User confirmation before commit; no direct main/master writes by default.
- Rollback guidance and audit history.

## Stage 4 — Test and build orchestration
- Project-specific command discovery from trusted project metadata.
- Sandboxed runner with CPU, memory, disk, network, and time limits.
- Allowlisted commands; no arbitrary shell execution by default.
- Stream logs and preserve exit codes.
- Separate build success from runtime/device verification.
- Never promise unlimited free cloud builds; stop if no free execution route exists.

## Stage 5 — Tool use and integrations
- Explicit tool registry with JSON schemas and permission scopes.
- Read-only tools first; write actions require confirmation.
- Network access constrained by domain and purpose.
- Secret redaction and prompt-injection defenses for retrieved content.
- User-visible activity log and cancel controls.

## Stage 6 — Project memory and knowledge retrieval
- Per-project decisions, architecture notes, API references, and known issues.
- Searchable curated docs with source attribution and versioning.
- Retrieve only relevant passages for each task.
- Distinguish project facts from model guesses.
- Allow user review and deletion of project memory.

## Stage 7 — Release and operations
- Reproducible artifacts and checksums.
- Signed release process and permission review.
- Backup/restore, health checks, monitoring, and crash diagnostics.
- Remote access only after authentication, TLS, pairing, and threat-model review.

## Acceptance criteria for every stage
- Feature is implemented in code.
- Tests or CI checks cover critical behavior.
- Failure and permission paths are tested.
- Documentation matches actual behavior.
- User can see what the agent is doing and approve sensitive actions.
