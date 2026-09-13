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

  * Allowed examples: inspecting status, diffs, logs, commits, and tracked files.
  * Do not modify the working tree, index, repository history, refs,
    configuration, or remotes.
  * Do not stage, commit, amend, stash, restore, checkout, switch, reset,
    clean, merge, rebase, cherry-pick, fetch, pull, push, or create/delete
    branches or tags.
  * Judge commands by their actual effects and arguments. A command that
    can inspect state must not be used with options that modify state.
  * If an operation requires Git writes, report it to the parent agent
    or user instead of executing it.
    
## Subagents

Use subagents according to the following responsibilities:
use English for subagents regardless main agent use which language.

* Use `code_explorer` for targeted repository exploration when the relevant implementation, execution path, or repository structure is not sufficiently understood.
* Use `code_worker` for implementing an established implementation specification and running relevant validation.
* Use `reviewer` as an independent quality gate for high-risk changes that justify the additional review cost.

The main agent acts as the senior engineer and owns implementation design, task classification, escalation decisions, and final decisions.

`code_worker` acts as the implementation engineer and should primarily execute the implementation specification produced by the main agent rather than independently designing the solution.

### Token-efficient tiered workflow

Use the lowest tier that is sufficient for the task. Do not invoke a subagent merely because it is available. Escalate only when uncertainty, implementation difficulty, or risk justifies the additional context and token cost.

#### Tier 0 — Main only

Use for trivial or mechanical work where delegation would cost more than it saves, for example:

* documentation, comments, or formatting;
* obvious renames or localized mechanical edits;
* very small low-risk code changes whose implementation and validation path are already clear.

The main agent may inspect, implement, and validate directly.

#### Tier 1 — Main -> code_worker

This is the default implementation tier when:

* the implementation path is already understood;
* the task follows an established repository pattern;
* the change is localized or otherwise straightforward to specify;
* there is no material security, authorization, concurrency, persistence, transaction, protocol, compatibility, lifecycle, rollback, or data-integrity risk.

Workflow:

1. Main agent inspects enough code to understand the change.
2. Main agent makes the implementation decisions and writes a compact execution specification.
3. `code_worker` implements the change and runs relevant validation.
4. Main agent evaluates the worker result and validation.

Do not invoke `reviewer` by default for Tier 1 work.

#### Tier 2 — code_explorer -> Main -> code_worker

Use when implementation uncertainty is the main problem, for example:

* the responsible files or symbols are not known;
* the relevant execution path or data flow must be traced;
* a bug's root cause is not yet established;
* the change crosses components and the current architecture is not sufficiently understood.

Workflow:

1. `code_explorer` performs targeted exploration and returns repository evidence.
2. Main agent compresses those findings into the minimum implementation-relevant context, resolves the design, and writes the execution specification.
3. `code_worker` implements the specification and runs relevant validation.
4. Main agent evaluates the result.

Do not repeat the explorer report verbatim in the worker delegation. Pass only facts, constraints, symbols, and decisions needed for implementation.

#### Tier 3 — code_explorer -> Main -> code_worker -> reviewer

Use for changes where independent review is worth the extra cost. Typical triggers include:

* security, authentication, authorization, or trust-boundary changes;
* concurrency, synchronization, race-condition, or ordering-sensitive behavior;
* persistence, transactions, migrations, consistency, or data-integrity risk;
* public API, ABI, protocol, schema, serialization, or compatibility changes;
* lifecycle, cleanup, rollback, resource ownership, or failure-recovery complexity;
* realistic risk of data loss, corruption, privilege escalation, or major production regression;
* large or high-impact cross-module changes.

Workflow:

1. Use `code_explorer` when repository evidence is needed to understand the affected behavior. If the implementation is already fully understood, the main agent may skip redundant exploration.
2. Main agent makes the implementation decisions and writes the implementation specification.
3. `code_worker` implements the specification and runs validation.
4. `reviewer` independently reviews the completed change against the specification and repository behavior.
5. Main agent evaluates findings and decides whether a focused follow-up worker task is required.

Do not use `reviewer` to implement changes.
Do not use `code_explorer` to modify files.
Do not delegate architectural decisions or an underspecified implementation problem to `code_worker` when the main agent can reasonably resolve those decisions first.

### Escalation rules

A task may start at a lower tier and be escalated when new information warrants it. Examples:

* Tier 1 -> Tier 2 when the worker or main agent discovers that the actual implementation path is unclear or materially different from the assumed one.
* Tier 1 or Tier 2 -> Tier 3 when the task reveals meaningful security, concurrency, persistence, compatibility, lifecycle, rollback, or data-integrity risk.

Do not pre-emptively escalate solely for caution. Prefer evidence-based escalation.

### Token-efficiency rules

* Prefer targeted repository reads and searches over broad scans.
* Reuse established findings instead of asking later agents to rediscover them.
* Treat the implementation specification as a compression layer: include what the worker needs to execute correctly, not a transcript of the investigation.
* Keep delegations concise when the task is simple; include detailed control flow, failure behavior, and invariants only when they materially affect correctness.
* Avoid repeating generic subagent rules already present in the subagent's own configuration.
* Use expensive independent review selectively, based on risk rather than task size alone.

---

## Implementation specification requirements

Before delegating an implementation task to `code_worker`, the main agent must
provide a concrete, right-sized implementation specification rather than only a
high-level goal.

The specification should be detailed enough that `code_worker` does not need to
independently design the solution, but concise enough to avoid repeating repository
evidence or generic rules that the worker already has. Scale detail with task risk
and complexity.

Include the following whenever applicable:

### 1. Goal

State precisely what behavior must be added, changed, fixed, or preserved.

### 2. Current implementation

Summarize the relevant existing behavior discovered from the repository,
including the important execution path, data flow, and responsibilities of
existing components.

Do not make `code_worker` rediscover information that `code_explorer` or the
main agent has already established.

### 3. Files and symbols

Identify the relevant:

* files,
* classes,
* functions,
* methods,
* interfaces,
* data structures,
* configuration entries,
* tests,

that should be modified or inspected.

When exact symbols are known, provide them.

### 4. Concrete implementation logic

Describe the intended implementation logic explicitly.

Specify, whenever relevant:

* control flow,
* data flow,
* algorithm,
* ordering of operations,
* state transitions,
* conditions and branches,
* ownership of state,
* validation rules,
* caching behavior,
* lifecycle behavior,
* concurrency expectations,
* cleanup behavior,
* persistence behavior,
* error propagation,
* failure recovery,
* invariants that must remain true.

Do not stop at statements such as:

"Add caching."

Instead specify the intended behavior, for example:

* Check the cache before invoking the resolver.
* Use `(projectId, version)` as the cache key.
* Cache only successful resolution results.
* Do not cache exceptions or missing results.
* Invalidate the corresponding entry when the project configuration changes.
* Preserve the existing public API.

### 5. Integration with existing code

Explain how the new logic should fit into the existing architecture.

Specify:

* which existing abstraction should be reused,
* which component should own the new behavior,
* which existing methods should call the new logic,
* which APIs should remain unchanged,
* which existing conventions or patterns should be followed.

Prefer extending the existing design over introducing parallel abstractions.

### 6. Edge cases and failure behavior

Identify important edge cases that must be handled.

For example:

* null or absent values,
* duplicate requests,
* partially completed operations,
* exceptions between multiple state-changing operations,
* invalid input,
* stale state,
* concurrent execution,
* resource cleanup,
* retries,
* backward compatibility.

Specify the expected behavior rather than leaving important edge-case decisions
to `code_worker`.

### 7. Scope boundaries

Explicitly state what should NOT be changed when useful.

For example:

* Do not change the public API.
* Do not migrate unrelated callers.
* Do not refactor the surrounding subsystem.
* Do not introduce a new abstraction for this task.
* Do not modify persistence schema.
* Do not change behavior outside this execution path.

### 8. Tests and validation

Specify the behaviors that validation should prove.

When possible, identify:

* existing tests to update,
* new tests to add,
* important test cases,
* expected outcomes,
* relevant build commands,
* relevant test commands,
* linters or type checks.

Tests should validate behavior, including relevant failure paths and edge cases,
rather than merely increasing coverage.

---

## Delegation quality

The main agent should think through the implementation before invoking
`code_worker`.

For non-trivial logic, provide pseudocode or ordered implementation steps when
they make the intended behavior clearer. For straightforward Tier 1 work, prefer
a compact execution packet over filling every optional section.

The main agent should decide:

* what the implementation should do,
* where the logic should live,
* how components should interact,
* what invariants must hold,
* how failures should behave,
* what tests should prove.

`code_worker` should normally decide only lower-level implementation details such
as:

* exact local variable names,
* minor syntax choices,
* equivalent project-style expressions,
* small mechanical adjustments required by the compiler or existing APIs.

Do not prescribe line-by-line code when repository conventions already make the
implementation obvious.

The goal is not to turn `code_worker` into a blind patch applicator. It may make
small implementation-level judgments, but it should not need to rediscover the
architecture or invent the solution.

---

## Delegation format

Every delegation to `code_worker` must include an `Applicable instructions`
section listing the exact paths of all `AGENTS.md` files checked by the main
agent and summarizing the constraints relevant to the task. The structured
packet below is recommended but may be adapted.

When delegating to `code_worker`, prefer a structured execution packet like:

### Task

<precise implementation goal>

### Current behavior

<relevant existing implementation and execution path>

### Applicable instructions

<exact paths to the `AGENTS.md` files already checked by the main agent>

<constraints from those instructions that affect this task; the main agent has
already performed this check, so do not rediscover applicable rules from
scratch>

### Files / symbols

* `path/to/File.kt` — `Class.method()`
* `path/to/Other.kt` — `OtherClass`
* `path/to/Test.kt` — relevant tests

### Implementation logic

1. <specific change>
2. <specific control-flow/data-flow behavior>
3. <specific integration behavior>
4. <failure/cleanup behavior>
5. <behavior that must remain unchanged>

### Edge cases

* <case and required behavior>
* <case and required behavior>

### Constraints

* <scope restriction>
* <architecture/API restriction>

### Validation

* <test to add/update>
* <command or validation to run>
* <expected behavior>

### Completion report

Report:

1. What changed.
2. Files changed.
3. Validation performed and results.
4. Any deviation from the specification.
5. Any unresolved issue or assumption.

---

## Handling uncertainty

If the main agent still has unresolved architectural or behavioral questions,
do not pass those questions to `code_worker` as an open-ended implementation
task.

Instead:

1. investigate them with `code_explorer` when repository evidence can answer
   them;
2. resolve the design at the main-agent level;
3. then delegate the resulting concrete specification.

If repository evidence is genuinely insufficient, make the safest reasonable
implementation decision at the main-agent level and clearly include that
assumption in the implementation specification.

---

## Handling worker deviations

`code_worker` must follow the implementation specification closely.

If `code_worker` discovers that the specification is impossible, unsafe,
incompatible with the repository, or clearly incorrect, it should not silently
redesign the solution.

It should:

* make only clearly safe progress within the established scope;
* report the conflict;
* explain the minimum required adjustment;
* leave architectural redesign decisions to the main agent.

The main agent then decides whether to revise the implementation specification
and delegate another focused implementation task.

## Subagent reliability

Before delegating repository work to a subagent:

1. Verify that the current session still has workspace and shell tools.
2. Ask every newly spawned repository subagent (`code_explorer`, `code_worker`,
   and `reviewer`) to verify workspace, shell, and filesystem access before
   beginning substantial work.
3. If any spawned repository subagent reports missing workspace, shell, or
   filesystem access, do not repeatedly retry delegation from the same session.
4. Report the runtime/tool provisioning failure to the user instead.

## 1. Agent Workflow

### Before Changing Code

Do not modify code immediately.

Before making code changes:

1. Inspect the relevant code and project instructions.
2. Explain the proposed changes to the user.
3. Provide a concrete implementation plan.
4. Ask for the user's approval.
5. Only execute the plan after approval.


### Project-Specific Instructions

Before working inside a directory, check whether that directory or any relevant parent directory contains an `AGENTS.md`.

Read and follow the applicable `AGENTS.md` instructions before modifying code.

---

## 2. File and Directory Safety

Never permanently delete project files or directories.

If a file or directory should be removed:

- Move it into `/DEL` instead.
- Preserve its contents and relative purpose where practical.

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

New classes should use packages under:

```text
calebxzau.*
```

Follow the existing module/package hierarchy when selecting the complete package name.

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

Run Gradle tasks through Windows PowerShell using `pwsh.exe` and a Windows path.

Do not run project Gradle tasks directly using the WSL Gradle environment.

Example conceptually:

```text
WSL
  -> pwsh.exe
  -> Windows project path
  -> module gradlew
```

Remember that there is no repository-wide root `gradlew`.

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

  Whenever an agent changes Modpack-server-side-logic related code in this repository, it must run the focused
  `modpackServiceTest` task from `server/master`.

  When running from WSL, use Windows pwsh.exe and the module-local Gradle wrapper:  
.\gradlew.bat modpackServiceTest --no-daemon -x :net:compileTestKotlin"

  If the task cannot run, report the exact blocker and do not claim that ModpackService validation passed.


## 15. Change Priorities

When implementing a feature that touches multiple modules, generally use this order:

1. Shared model/DTO changes in `common`
2. Backend/service changes
3. Client integration
4. UI changes
5. Focused tests

Adjust the order when dependencies make another sequence more appropriate.

Before implementation, still follow the approval requirement, then use the
lowest sufficient execution tier:

```text
Inspect
-> Classify Tier
-> Plan
-> Ask for approval
-> Execute with the selected tier
-> Validate
-> Escalate only if new uncertainty or risk requires it
```

