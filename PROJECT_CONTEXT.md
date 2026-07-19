# MaxVibes Project Context

## Architecture
Multi-module Kotlin IntelliJ Plugin (Clean Architecture):
- maxvibes-domain — models, ports interfaces
- maxvibes-application — use cases
- maxvibes-adapter-psi — IntelliJ PSI integration
- maxvibes-adapter-llm — LangChain4j providers
- maxvibes-plugin — UI, Tool Window, Actions

## Key flows
1. User sends message in Tool Window
2. Planning phase: LLM picks relevant files
3. Chat phase: LLM generates PSI modifications
4. Modifications applied via PsiModifier

## PSI operations
REPLACE_ELEMENT, CREATE_ELEMENT, DELETE_ELEMENT, ADD_IMPORT, REMOVE_IMPORT

## Tech stack
Kotlin, IntelliJ Plugin SDK, LangChain4j, Gradle (multi-module)