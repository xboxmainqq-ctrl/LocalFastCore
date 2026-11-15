# NEXORA AI project workspace and multi-file agent

## Current workflow
1. The user connects GitHub and selects a repository and branch.
2. The workspace lists a folder and captures the current branch head SHA.
3. The user selects up to 20 text files and loads them into bounded AI context. Each file is paired with its blob SHA.
4. The user asks for a multi-file change. The model returns a strict JSON plan containing update/create/delete operations.
5. The dashboard validates relative paths, allowed actions, context membership, folder listings for new files, file count, and staged size.
6. The user confirms applying the plan to staging. Every staged file can be previewed or removed.
7. Before commit, the user sees the file list and confirms. The Android bridge compares the current branch head with the captured snapshot SHA. If the branch changed, it aborts instead of writing on top of an unknown state.
8. GitHub commit writes the staged tree in one commit. The workspace clears stale context and asks the user to reload.

## Safety boundaries
- The branch snapshot is a coarse optimistic concurrency guard. It detects branch movement since the folder was loaded; it is not a substitute for a full local Git index or merge engine.
- The model can only reliably edit files included in context. It must not invent unseen code.
- New files are allowed only inside a folder whose listing was loaded; a file already present in that listing is rejected.
- Deletion is an explicit staged operation and is not sent until the user confirms the commit.
- Maximum AI context: 20 files and 14,000 source characters before request framing. The chat bridge also applies its own request/history bounds.
- Maximum commit: 50 file operations and 1 MiB total new text content. The current AI planner limits plans to 20 files.
- Current file reader supports UTF-8 text files up to 512 KiB.
- No terminal, Gradle runner, arbitrary shell, automatic tests, or device runtime validation is provided by this workspace yet.

## Known limitations / next improvements
- Add a real unified diff renderer and line-level conflict resolution.
- Add a repository tree manifest with pagination and ignore rules for generated/binary/secrets files.
- Add file-specific blob SHA verification in addition to the branch-head snapshot.
- Add a sandboxed execution service with strict CPU, memory, disk, network, and time limits before enabling build/test commands.
- Add retry/resume for context reads and clearer progress/cancellation controls.
- Add tests for JSON-plan validation, path traversal, duplicate paths, branch races, create/delete tree operations, and quota/size limits.
