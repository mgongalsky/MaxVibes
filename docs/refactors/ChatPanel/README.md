# ChatPanel monster -to - facade refactor

## Status

Completed.`ChatPanel` was reduced from a large multi - responsibility UI controller to a thin IntelliJ - facing facade . The final implementation delegates Swing rendering, composition, session flows, mode management, runtime lifecycle, transcript restoration, prompt files and controller callbacks to focused collaborators .

All plugin tests passed after the final cut .

## Original problem

        The original `ChatPanel` combined too many responsibilities in one class :

    - Swing component construction and layout
-chat transcript restoration
-session create, branch, rename, select and delete flows
        -interaction - mode switching
        -clipboard state transitions
-live Claude Code stream routing
-subscription usage polling
-prompt - file create, edit and delete operations
        -IntelliJ environment actions
-construction of `ChatPanelState`
-implementation of the complete `ChatPanelCallbacks` port
        -controller and service wiring
        -lifecycle and disposal

This made the class difficult to understand, characterize and modify safely .

## Refactoring strategy

        The refactor followed a characterization - first monster -to - facade workflow :

1.Preserve externally visible behavior with characterization tests.2.Identify coherent responsibilities and stable seams .
3.Extract one responsibility at a time behind narrow interfaces or callbacks.4.Keep `ChatPanel` as the stable public entry point .
5.Run tests after every cut.6.Finish with large composition and view cuts only after the smaller boundaries were proven .

## Extracted components

### Existing child views

-`ChatHeaderPanel`
-`ChatInputPanel`
-`SpecificPromptPanel`
-`ClaudeCliSettingsPanel`
-`ConversationPanel`
-`PlanPanel`
-`LiveTurnPanel`
-`LimitsBarPanel`

These own their Swing widgets and expose behavior through callbacks .

### Session transcript

        -`SessionTranscriptRenderer`
-`SessionTranscriptView`
-`ConversationPanelTranscriptView`

Responsibilities:

-clear and rebuild persisted transcript state
        -show branch ancestry
-route USER, ASSISTANT and SYSTEM messages
        -restore persisted modification paths
        -preserve assistant metadata

### Prompt - file operations

        -`SpecificPromptFiles`
-`SpecificPromptFileActions`

Responsibilities:

-resolve prompt files
-create unique prompt files
        -delete prompt files
-coordinate open, status, selection clearing and UI refresh

### Session UI coordination

-`ChatSessionUiCoordinator`
-`ChatSessionDialogs`
-`SwingChatSessionDialogs`

Responsibilities:

-load the active session
        -render the welcome state
        -select, create, branch, rename and delete sessions
        -coordinate confirmation dialogs and status updates

### IntelliJ environment actions

-`ChatPanelEnvironmentActions`

Responsibilities:

-clipboard trace attachment
-context - file dialog
        -prompt editor action
-Claude instructions popup
-Claude Code log opening
        -plan document opening
-tool - window maximize and floating state
-VCS commit -message injection

### Controller callbacks

        -`ChatPanelCallbacksAdapter`

Responsibilities:

-implement the complete `ChatPanelCallbacks` port
-route transcript, input, session, command, question and attachment callbacks
        -normalize system messages
-keep `ChatPanel` independent from callback implementation details

### Immutable render state

-`ChatPanelStateFactory`

Responsibilities:

-collect the active session snapshot
-collect mode, attachments, context and prompt state
        -calculate Claude Code approve visibility
-construct `ChatPanelState`

### Mode coordination

        -`InteractionModeState`
-`InteractionModeManager`
-`ChatModeCoordinator`
-`ChatModeDialogs`
-`SwingChatModeDialogs`

Responsibilities:

-persisted mode state
-initialization from settings
-clipboard reset confirmation
-mode switching and labels
        -indicator actions
        -application of `ModeUiPolicy`

### Runtime lifecycle

        -`ChatRuntimeCoordinator`

Responsibilities:

-register and unregister `AgentStreamHub` listener
-route active -session events
        -route rate -limit events
        -start and stop subscription usage polling
        -idempotent lifecycle management

### Swing view

        -`ChatPanelView`
-`ChatPanelViewActions`

Responsibilities:

-own all child Swing panels
-build the complete layout
        -render `ChatPanelState`
        -expose narrow UI operations
        -emit user actions through callbacks
-own view -specific cleanup

### Composition root

        -`ChatPanelComposition`

Responsibilities:

-construct the controller, factories, coordinators and adapters
-wire dependencies and callbacks
        -initialize the composed chat UI
-implement send flow
-expose facade operations
-dispose runtime and view resources

## Final ChatPanel responsibility

`ChatPanel` is now a thin IntelliJ - facing facade .

It only :

-creates `ChatPanelComposition`
        -mounts `composition.view`
        -starts the composition
-registers with IntelliJ disposal
        -delegates `refreshHeader`
        -delegates `loadCurrentSession`
        -delegates `acceptPrefill`
        -delegates `dispose`

        It no longer owns business flows, controller callbacks, Swing layout or dependency wiring.

## Resulting structure

`text
ChatPanel
└── ChatPanelComposition
├── ChatPanelView
│   ├── ChatHeaderPanel
│   ├── ChatInputPanel
│   ├── ConversationPanel
│   ├── SpecificPromptPanel
│   ├── ClaudeCliSettingsPanel
│   ├── PlanPanel
│   ├── LiveTurnPanel
│   └── LimitsBarPanel
├── ChatMessageController
├── ChatPanelCallbacksAdapter
├── ChatPanelStateFactory
├── ChatSessionUiCoordinator
├── ChatModeCoordinator
├── ChatRuntimeCoordinator
├── ChatPanelEnvironmentActions
└── SpecificPromptFileActions
`

## Tests added or extended

        The refactor is protected by focused tests for the extracted boundaries:

-`ChatPanelChildViewsCharacterizationTest`
-`ConversationRendererTest`
-`SessionTranscriptRendererTest`
-`SpecificPromptPanelTest`
-`SpecificPromptFilesTest`
-`SpecificPromptFileActionsTest`
-`ClaudeCliSettingsBinderTest`
-`ClaudeCliSettingsPanelTest`
-`ChatSessionUiCoordinatorTest`
-`ChatPanelCallbacksAdapterTest`
-`ChatPanelStateFactoryTest`
-`ChatModeCoordinatorTest`
-`ChatRuntimeCoordinatorTest`

The complete `:maxvibes-plugin:test` suite was green after the final composition cut.

## Design outcome

        The final structure follows these rules :

-`ChatPanel` is a facade, not a controller dump .
-Swing code lives in view classes .
-orchestration lives in coordinators .
-construction lives in one composition root .
-controller - facing ports are implemented by an adapter.
-state construction is deterministic and testable .
-environment - specific actions are isolated .
-runtime subscriptions have an explicit owner .
-session transcript restoration is independent from the panel lifecycle .

No further ChatPanel decomposition is planned as part of this refactor .
