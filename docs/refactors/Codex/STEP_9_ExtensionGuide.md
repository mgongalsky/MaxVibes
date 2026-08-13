# Step 9 — Adding Another Coding - Agent Provider

## Rule

Do not create another interaction stack .

A new provider plugs into the existing flow :

`UI -> CodingAgentInteractionService -> CodingAgentCliPort -> provider adapter`

Provider - specific wire protocols stay below `CodingAgentCliPort` .

## 1.Add the provider identity

        Add the provider to `CodingAgentProvider`.Do not add provider -specific session fields such as `newAgentSessionId` to `ChatSession` . Use `CodingAgentSessionRef(provider, remoteSessionId, needsFullContext)`.

## 2.Define provider policy

Extend `CodingAgentProviderPolicy.forProvider()` .

Decide explicitly :

-display name
        -log tag
        -where the MaxVibes system instruction is delivered
-process start
        -request protocol

        Do not infer Claude behavior for another provider .

## 3.Add the provider prompt

        Add a provider - specific prompt through `PromptPort` / `PromptService` when required .

The prompt must preserve the MaxVibes contract:

-project context is requested through `requestedViews`
        -edits are returned through `modifications`
-shell work is requested through `commands`
        -questions use the shared protocol
-the coding agent must not bypass MaxVibes by directly editing project files

## 4.Implement `CodingAgentCliPort`

        The provider adapter must implement:

-`isAvailable()`
-`ensureStarted(resumeSessionId, systemPrompt)`
-`send(request)`
-`shutdown()`
-`abort()` when provider -specific interruption is needed

        The adapter owns only transport / session mechanics . It must not duplicate approval, requested - view or modification orchestration .

## 5.Keep the raw parser provider - specific

Do not generalize incompatible wire formats into one parser.Examples:

-Claude: stream - JSON parser
        -Codex: JSON - RPC / App Server parser

Parse raw provider events locally, then normalize live information into `AgentStreamEvent` and final responses into the shared `InteractionResponse` protocol .

Parsers should be tolerant of unknown notifications so provider upgrades do not break the dialog for harmless new event types .

## 6.Preserve the MaxVibes safety boundary

Coding - agent CLIs are reasoning / transport runtimes, not direct project editors .

Recommended provider configuration:

-filesystem read -only where supported
-no autonomous approval escalation
        -no direct project mutation

        Any provider -specific start / resume API must preserve these constraints on * * both new and resumed sessions * *.

        Do not assume resume inherits the original policy; Codex demonstrated that it may not .

## 7.Wire the provider, not another mode

Add the provider to the Coding Agent provider selector.Reuse:

-existing Coding Agent dispatcher
        -existing background executor
-existing approval flow
-existing requested -view flow
        -existing command -result routing
        -existing stream hub

Avoid adding a new `InteractionMode` unless the product interaction itself is genuinely different from Coding Agent mode.

## 8.Keep provider services lazy

        Create provider transports lazily in `MaxVibesService` so unused CLIs do not spawn processes or allocate resources .

Provider selection should resolve the appropriate `CodingAgentInteractionService` configured with:

-the provider transport
-the correct `CodingAgentProvider`
-shared repositories / services

        Dispose and abort paths must include the new adapter without forcing lazy initialization.

## 9.Add focused tests

Do not copy the entire shared interaction test suite per provider.Add tests for the new seams:

-provider policy
        -parser mapping
        -error mapping
        -session - id extraction
        -start / resume behavior
        -safety configuration
        -provider - specific prompt delivery

Keep the shared `CodingAgentInteractionService` characterization suite provider - independent.

## 10.Run a real smoke test

Before considering the adapter complete, verify against the actual installed CLI / runtime :

1.availability / version
2.initialization / handshake
3.new session / thread
        4.one real turn
5.streaming
6.authoritative final response
7.token usage / statistics payload
8.cold resume after process restart
9.restored conversation history
10.safety policy after resume

        The Codex integration found two real contract bugs only through this smoke step: nested token usage and lost sandbox / approval policy during resume.

## Definition of done

A provider is complete when it can replace Claude Code / Codex behind `CodingAgentCliPort` without requiring a new application orchestration path.
