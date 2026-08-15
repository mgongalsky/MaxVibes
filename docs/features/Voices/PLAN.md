# Voice Transcription — Implementation Plan

## Goal

Add a microphone button to the MaxVibes chat input.The first click starts recording, the second stops it and sends the audio to a configurable OpenAI -compatible transcription API.The returned transcript is inserted at the caret and is never submitted automatically .

If voice transcription is not configured, clicking the microphone opens `Settings → Tools → MaxVibes` instead of attempting to record .

## Product decisions

        -MVP uses a cloud OpenAI - compatible `multipart/form-data` transcription endpoint .
-Default endpoint : `https://api.openai.com/v1/audio/transcriptions` .
-Default model : `gpt-4o-mini-transcribe`; the model remains editable so `whisper-1` or another compatible model can be selected without a plugin update.
-Voice API credentials are configured independently from the main chat provider and stored in IntelliJ PasswordSafe .
-The request includes a small prompt / glossary but no second LLM correction pass .
-The transcript is inserted into the input field for review; it is not sent automatically .
-Temporary audio is held only for the request and deleted immediately afterwards .
-One recording can be active at a time . Sending and starting another recording are disabled while transcription is in progress .

## Configuration

Add a `Voice transcription` section to `MaxVibesSettingsPanel` :

-API key — stored in PasswordSafe as `voice_transcription_api_key` .
-Endpoint — persisted, with the OpenAI transcription URL as default.
-Model — persisted, editable, with `gpt-4o-mini-transcribe` as default .
-Language — optional ISO -639 - 1 hint; blank means automatic detection .
-Glossary — optional user -maintained comma / newline -separated terminology .

`MaxVibesSettings.isVoiceConfigured` requires a non -blank API key, endpoint, and model .

When the microphone is clicked without a valid configuration, open `MaxVibesSettingsConfigurable` directly through IntelliJ `ShowSettingsUtil` . The settings panel should make the voice section easy to locate .

## Context prompt

        Build a bounded prompt from:

1.Project name .
2.User - configured glossary .
3.Names of currently relevant files / elements when cheaply available.4.A small set of MaxVibes defaults : Kotlin, IntelliJ IDEA, PSI, Codex, Claude Code, MaxVibes.The prompt is a terminology hint only.It must not contain source bodies, secrets, full chat history, or large project context . Deduplicate terms and enforce a conservative character limit before sending.

## Architecture

Keep the feature behind ports so local Whisper can be added later without changing the UI .

### Application layer

        Add:

-`VoiceTranscriptionPort` — accepts audio bytes plus MIME type and transcription options, returns a typed result .
-`VoiceTranscriptionRequest` / `VoiceTranscriptionError` — transport - neutral models .
-`VoiceTranscriptionService` — validates input, bounds the context prompt, and delegates to the port.The application layer must not depend on Swing, IntelliJ APIs, files, or Java Sound.

### Plugin adapters

        Add:

-`JavaSoundVoiceRecorder` — captures mono PCM through `TargetDataLine` and produces WAV bytes.
-`OpenAiCompatibleTranscriptionAdapter` — sends multipart HTTP requests and parses the returned `text` field .
-`VoiceInputCoordinator` — owns the `IDLE → RECORDING → TRANSCRIBING → IDLE` state machine, background execution, cancellation / cleanup, status reporting, and transcript insertion.
-`VoiceContextPromptBuilder` — creates the bounded terminology prompt from project / UI context .

Use JDK / IntelliJ HTTP facilities where practical; avoid adding a large SDK solely for one multipart endpoint.

### Composition

Expose the transcription service through `MaxVibesService` and construct the UI coordinator in `ChatPanelComposition`.Keep `ChatInputPanel` as a project - agnostic view driven by callbacks / state.

## UI behavior

        Add a microphone button near `Send` :

-Idle: microphone icon, tooltip `Start voice input` .
-Recording: visually active, tooltip `Stop and transcribe`; status shows recording state .
-Transcribing: disabled / busy state; status shows transcription progress .
-Success: insert transcript at the current caret, separating it from existing text with whitespace when needed, then focus the input .
-Failure: restore idle controls and show an actionable status / error without losing existing input .
-Missing microphone or denied access: show a clear message and remain usable.
-Missing configuration : open Settings immediately; do not start audio capture .

All Swing updates must run on the EDT . Recording and network I / O must run off the EDT .

## Delivery steps

### Step 1 — Application contract and tests

        Create transport -neutral request / error models, `VoiceTranscriptionPort`, and `VoiceTranscriptionService` . Test validation, prompt bounds, success propagation, and typed failures.

### Step 2 — Persistent settings

        Extend `MaxVibesSettings` with endpoint, model, language, glossary, secure voice API key, and `isVoiceConfigured` . Add settings round -trip / configuration tests where feasible .

### Step 3 — Settings UI

        Add the Voice transcription section to `MaxVibesSettingsPanel`, including load / save / isModified behavior and safe password handling.Keep the existing main OpenAI key independent.

### Step 4 — Cloud transcription adapter

Implement multipart upload with fields `file`, `model`, optional `language`, and optional `prompt`.Parse successful JSON responses, map authentication / rate -limit / server / network / malformed - response failures, enforce timeouts, and never log the API key or audio payload.Add adapter tests with a local fake HTTP server so tests do not call a real provider.

### Step 5 — Microphone recorder

        Implement WAV capture using Java Sound with a broadly supported mono PCM format.Ensure line closure and buffer cleanup on stop, failure, and project disposal.Unit - test WAV assembly separately from physical microphone access .

### Step 6 — Coordinator and composition

Implement the voice state machine and wire settings, recorder, prompt builder, transcription service, lifecycle disposal, and UI callbacks through `MaxVibesService` / `ChatPanelComposition`.

### Step 7 — Chat input UI

Add the microphone control and explicit rendering methods for voice state . Preserve the existing project - free callback design of `ChatInputPanel`.Insert the transcript at the caret rather than replacing or auto - sending existing text.

### Step 8 — Verification

Run focused unit tests, the plugin test suite, and a plugin build . Smoke -test:

-unconfigured button opens `Settings → Tools → MaxVibes`;
-configured button starts / stops recording;
-Russian speech with English / Kotlin identifiers uses the glossary;
-transcript is inserted but not sent;
-API / network / microphone errors recover to idle;
-temporary audio and capture resources are released;
-project / tool - window disposal during recording is safe .

## Out of scope for MVP

-Local Whisper model installation or inference .
-Streaming / partial transcription .
-Voice activity detection and automatic stop .
-Post - transcription LLM correction.
-Persisting audio or attaching it to chat history .
-Provider - specific SDKs or multiple dedicated provider UIs.
