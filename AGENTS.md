## Project Overview

This project is a Minecraft multiplayer hosting system.

Users can upload Minecraft modpacks, create a host, and invite friends to play together.

When designing user-facing features, prefer Minecraft/player terminology over infrastructure terminology. Hide unnecessary implementation details from users whenever possible.

For example:
- Prefer `host`（房间） over `Docker container`.
- Do not expose backend orchestration or deployment details unless the user specifically needs them.

you can use intellij MCP to check code errors if available

---
## Git access

Only read-only Git operations are allowed.

* Inspecting status, diffs, logs, commits, and tracked files is allowed.
* Do not use Git to modify the working tree, index, history, refs, configuration,
  or remotes. This restriction does not prohibit authorized source-file edits.
* Do not stage, commit, amend, stash, restore, checkout, switch, reset, clean,
  merge, rebase, cherry-pick, fetch, pull, push, or create/delete branches or tags.
* Judge commands by their actual effects and arguments. Report any required Git
  write operation to the parent agent or user instead of executing it.

## Subagents

Use English for subagent tasks and reports.

The main agent owns implementation design, scope, and final decisions. Handle
ordinary localized work directly; delegate when independent context, substantial
investigation, or parallel execution justifies the handoff. There is no mandatory
exploration/implementation/review pipeline.

* Use `code_explorer` for substantial investigation or unclear execution paths.
  Skip it when the relevant repository behavior is already understood.
* Use `code_worker` for a bounded implementation task that can be executed
  independently from a concrete specification.
* Use `reviewer` for independent review when the change introduces material
  security, concurrency, data-integrity, compatibility, or lifecycle/recovery
  risk. Do not require review solely because several files changed.
* Explorers and reviewers do not edit files or implement fixes.

### Delegation

Give workers a compact specification covering:

1. Goal: the required behavior and relevant current behavior.
2. Scope: owned files/symbols, integration points, and changes to avoid.
3. Constraints: behavior contracts, key implementation decisions, and invariants.
4. Validation: acceptance criteria and relevant checks.

Include an `Applicable instructions` section with the exact paths of checked
`AGENTS.md` files and the constraints relevant to the task. Add state transitions,
ordering, failure recovery, or pseudocode only when the task needs them. Reuse
established findings instead of repeating broad exploration or generic rules.

Workers may decide local function decomposition, helper reuse, syntax, and test
organization within the specification. Changes to behavior contracts, scope,
architecture, or risk require the parent agent's decision. If the specification
conflicts with repository evidence, report the conflict and minimum adjustment;
continue only work that is clearly safe within the approved scope.

### Shared workspace and validation

* Preserve edits made by the user and other agents.
* Assign non-overlapping file ownership to parallel workers; coordinate before
  editing a shared file.
* Serialize Gradle runs for the same module and across modules that share build
  outputs. Do not terminate another agent's build.
* Report changes, validation results, deviations, and unresolved issues concisely.
  Distinguish implementation failures, pre-existing failures, and checks not run.

### Tool failures

Verify that the parent has workspace access before delegation. Each new subagent
should verify the tools needed for its task through its first normal read or
command; a separate repetitive preflight is unnecessary.

If a subagent lacks required tools, do not repeatedly respawn it in the same
session. The main agent may take over already authorized work using available
tools. Report remaining tool or validation limitations. If independent review
cannot run, report that gap explicitly; the main agent's self-review does not
count as independent approval.

## 1. Agent Workflow

### Before Changing Code

Do not modify code immediately.

Before making code changes:

1. Inspect the relevant code and project instructions.
2. Explain the proposed changes and provide a concrete implementation plan.
3. Obtain the user's approval before implementing the plan.

Approval covers delegated implementation, necessary validation, and fixes within
the approved scope. Do not ask again merely because work moves to a subagent or
validation finds an implementation error. Ask again only when the plan materially
expands scope or changes behavior the user has already confirmed.


### Project-Specific Instructions

Before working inside a directory, check whether that directory or any relevant parent directory contains an `AGENTS.md`.

Read and follow the applicable `AGENTS.md` instructions before modifying code.

---

## 2. File and Directory Safety

Never permanently delete project files or directories.

If a file or directory should be removed:

- Move it into `DEL/<unique-archive-directory>/<original-relative-path>` under
  the repository root, not the filesystem root.
- Preserve its contents and original relative path.
- Choose a new archive directory for each removal operation; never overwrite an
  existing archived file.

Do not use destructive deletion commands for project files.

---

## 3. Project Structure

There is no root `gradlew` for the entire repository.

Run Gradle commands from the corresponding module/project directory.

Main modules:

### `client/ui/`

Desktop launcher and UI.

Contains:
- Compose for Desktop UI
- Game installation logic
- Game launching logic
- Screens
- Reusable UI components

UI assets and icons are located under:

```text
client/ui/assets/src/main/resources/assets
````

### `common/`

Shared code used by multiple modules.

Contains:

* Shared models
* DTOs
* Network helpers
* Parsing services
* Shared utilities

When changing a shared DTO or model:

1. Update `common` first.
2. Then update client/server callers.

### `server/master/`

Main backend.

Technologies include:

* Ktor
* MongoDB
* Host orchestration

### `server/proxy/`

Proxy service.

### Standard Source Layout

Gradle Kotlin modules generally use:

```text
src/main/kotlin
src/main/resources
src/test/kotlin
```

---

## 4. UI Guidelines

### Material

Always use Material 3.

Do not introduce or use Material 2 components.

### Buttons

Prefer `CircleIconButton` whenever it is suitable for the interaction.

For icon-based actions, consider `CircleIconButton` before introducing another button style.

### User-Facing Terminology

Avoid exposing technical implementation details to users unless necessary.

For example:

Prefer:

```text
Host
```

instead of:

```text
Docker container
```

User-facing text should describe what the feature means to a Minecraft player rather than how the backend implements it.

---

## 5. Kotlin Style and Naming

Use:

* Kotlin
* UTF-8
* 4-space indentation

Naming conventions:

| Element      | Convention         | Example            |
| ------------ | ------------------ | ------------------ |
| Class / type | `PascalCase`       | `HostManager`      |
| File         | `PascalCase`       | `HostManager.kt`   |
| Enum type    | `UpperCamelCase`   | `ContentPlatform`  |
| Enum entry   | `UpperCamelCase`   | `CurseForge`       |
| Function     | `camelCase`        | `createHost()`     |
| Variable     | `camelCase`        | `hostId`           |
| Constant     | `UPPER_SNAKE_CASE` | `MAX_PLAYER_COUNT` |

Compose naming:

* Screens: `*Screen.kt`
* Reusable cards: `*Card.kt`
* Reusable buttons: `*Button.kt`
* Other reusable components should follow the same descriptive naming style.

---

## 6. Kotlin Package Names

Only newly created packages should use the prefix:

```text
calebxzau.*
```

When adding or modifying a class in an existing package, preserve that package's
name, including existing `calebxzhou.*` packages. Do not migrate existing packages
as part of this rule.

---

## 7. UUIDs

When introducing a model that requires a UUID, use UUIDv7.

Do not introduce UUIDv4 for new model identifiers unless there is a specific compatibility requirement.

---

## 8. Kotlin String Templates

When a Kotlin string template variable directly touches surrounding text, always use braces.

Prefer:

```kotlin
"测试${abc}测试测试"
```

Do not write:

```kotlin
"测试$abc测试测试"
```

Simple unambiguous templates separated by whitespace may remain unbraced.

---

## 9. Fallible Kotlin Operations

Functions that can reasonably fail should normally expose that failure explicitly.

Examples include:

* Disk I/O
* Network I/O
* File parsing
* External process execution
* Remote API calls
* Other operations with expected runtime failure modes

Prefer:

```kotlin
fun loadSomething(): Result<Something>
```

over silently returning `null` for failures.

### Handling `Result`

At the call site, prefer explicit handling such as:

```kotlin
runCatching {
    ...
}.getOrElse { exception ->
    logger.error("Failed to ...", exception)
    ...
}
```

or:

```kotlin
result.getOrThrow()
```

when propagation is appropriate.

Reduce the use of:

```kotlin
getOrNull()
```

for failures that should be observable.

Exceptions should normally be:

* Explicitly logged with the exception attached, or
* Propagated/thrown when the caller is responsible for handling them.

Do not silently swallow meaningful failures.

---

## 10. Line Endings

Do not spend time normalizing CRLF/LF differences in general project files.

Exceptions:

* `Dockerfile` must use LF.
* `*.sh` files must use LF.

---

## 11. WSL and Windows

When running inside WSL:

### Gradle Cache

If Gradle cache contents need to be inspected, use the Windows host Gradle cache.

Do not inspect the WSL `~/.gradle` cache as the authoritative project cache.

### Running Gradle

1. First run Gradle through IntelliJ MCP on the Windows host.
2. If MCP is unavailable, the tool call fails, or it cannot start the command,
   fall back to Windows `pwsh.exe` and the module-local `gradlew.bat`.
3. In either case, run from the corresponding Windows module/project directory.
   There is no repository-wide root `gradlew`.

A Gradle compile/test failure after the command starts is a validation result,
not an MCP failure. Diagnose it instead of rerunning through another entry point.

Do not run project Gradle tasks using the WSL Gradle environment.

---

## 12. Chinese Text Formatting

Do not add unnecessary spaces between Chinese characters and numbers or Latin letters.

Prefer:

```text
Mod数量8个
```

Do not write:

```text
Mod 数量 8 个
```

Follow the same convention for user-facing Chinese text unless spacing is required for readability or syntax.

---

## 13. Minecraft Source Code References

Minecraft/loader source code is available locally and may be inspected when implementation behavior needs to be verified.

### Minecraft 1.21.1 + NeoForge

Source location:

```text
client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-${neoforge-version}-sources
```

### Minecraft 1.20.1 + Forge

Source location:

```text
client/mc/1.20.1-forge/build/moddev/artifacts/forge-1.20.1-${forge-version}-sources
```

Prefer consulting these sources when behavior depends on Minecraft, Forge, or NeoForge internals rather than guessing their implementation.

---

## 14. Testing

Testing stack:

* Kotlin Test
* JUnit Platform

Test files should use the `*Test.kt` suffix.

Examples:

```text
HostTest.kt
ModpackTest.kt
```

Prefer focused unit tests for:

* Parsing
* Mapping
* Service logic
* Data transformations
* Business rules

Avoid network-dependent tests unless external dependencies are mocked.

Some `server/proxy` test tasks may currently be disabled in Gradle.

When adding or maintaining tests there, verify whether the relevant test task needs to be enabled.

---

### Modpack Changes

Whenever an agent changes Modpack server-side logic, run the focused
`modpackServiceTest` task from `server/master`. Use IntelliJ MCP first and Windows
`pwsh.exe` only as the fallback described above, with the module-local wrapper:

```powershell
.\gradlew.bat modpackServiceTest --no-daemon -x :net:compileTestKotlin
```

If the task cannot run, report the exact blocker and do not claim that
ModpackService validation passed.


## 15. Change Priorities

When implementing a feature that touches multiple modules, generally use this order:

1. Shared model/DTO changes in `common`
2. Backend/service changes
3. Client integration
4. UI changes
5. Focused tests

Adjust the order when dependencies make another sequence more appropriate.

Follow the approval and delegation rules above. Execute and validate within the
approved scope; revisit the plan only when new evidence requires a material change.
