# playlet-generator Guide

## Coding Rules

* **Plan Before Coding**: For user requirements, you MUST first output a concise implementation plan (including affected files and core logic changes). Then, STOP and wait for the user's explicit approval (e.g., "Proceed") before writing or modifying any code.
* **Ask, Don't Guess**: If you encounter critical missing context, ambiguous business logic, or any information that could lead to destructive changes, it is **mandatory** to ask the user for clarification. Do NOT make assumptions.

## Project Layout

### Backend
* `backend` is the backend root directory.
* `playlet-agent` is the module for agent-related capabilities.
* `playlet-api` provides RESTful interfaces.
* `playlet-comfyui` is the module interacting with ComfyUI.
* `playlet-domain` is the entity module.
* `playlet-llm` provides LLM capabilities.
* `playlet-media` provides media capabilities.

### Frontend
* `frontend` is the root directory for frontend pages.

### Key Components
* `playlet-api/src/main/java/org/playlet/api/web`: Provides RESTful interfaces.
* `playlet-comfyui/src/main/java/org/playlet/comfyui/backend/video/MiniMaxH3Backend.java`: Core class for video generation.
* `playlet-comfyui/src/main/java/org/playlet/comfyui/backend/image/AnimaImageBackend.java`: Core class for image generation.
* `playlet-comfyui/src/main/java/org/playlet/comfyui/template/WorkflowImporter.java`: Core class for importing and parsing ComfyUI workflows.

## Building
* You **MUST** read `docs/dev/BUILDING.md` before building or running. Do **NOT** guess build commands.

## Docs
* `CHANGES.md` records the **5 most recent changes**. After delivering a feature, you **MUST** prepend a brief summary of the changes to the beginning of this document, using the format: `- [YYYY-MM-DD] Core change content (note the reason if it involves architectural decisions)`, and keep it **under 50 words**.
* This document holds a **maximum of 5 records**. If a new record exceeds this limit, you **MUST automatically** move the oldest record to the top of `docs/dev/CHANGES_ARCHIVED.md`.
* `CHANGES_ARCHIVED.md` is for archive purposes only. **Reading this document directly is FORBIDDEN.**

## Delivery Standards
After you finish writing the code for a feature module, and it passes testing, in addition to updating `CHANGES.md`, you **MUST** update the following two core documents in sync. Keep the content concise:

1. **Update Interface Contract (`docs/api/${controllerClassName}.md`)**:
   - Extract the newly added/modified interface signatures, API paths, and request/response parameters (expressed as JSON Schema or simple request/response examples).
   - Append or update the content in the corresponding module section of the file. Do not write implementation logic.
   - **Naming Convention**: `${controllerClassName}` is the current Controller filename, converted to lowercase and stripped of the `Controller` suffix. For example, `UserController.java` corresponds to `docs/api/user.md`.
   - **Update Logic**: If modifying an existing interface, update it in place within the original file. Do **NOT** append duplicate old definitions.

2. **Update Module Map (`docs/${group}/${module}/moduleMap.md`)**:
   - Check the core files involved in this change.
   - Describe the file's responsibility in one sentence.
   - **Strict Limit**: ONLY include Controllers, Services, core business logic, and shared utilities. Do NOT add DTOs, enums, test files, or auto-generated code.
   - **Update Logic**: Only add new lines or modify existing lines. DO NOT rewrite the file.
   - **Path Resolution**:
     - `${group}` is `backend` if the modified file is under the `backend/` directory, otherwise `frontend`.
     - If `${group}` is `backend`, `${module}` is the name of the top-level submodule directory (e.g., `playlet-api`, `playlet-agent`).
     - If `${group}` is `frontend`: ALWAYS use the single file `docs/frontend/moduleMap.md`. Do NOT create sub-module maps.
   - **Frontend Specifics**:
     - Only record files in `src/components/` (top-level), `src/hooks/`, `src/lib/`, and `src/types/`.
     - **FORBIDDEN** to list files inside `src/components/ui/`.

**Exemption Rule:** If the change is only a typo fix, formatting adjustment, or pure internal refactor (no interface signature or file responsibility changes), you may skip these two document updates and only record it in `CHANGES.md`.

**Note:** Do **NOT** output large blocks of code or filler in the above documents; keep them high-density.

**After completing the document updates, reply only with "Updated docs/api/xxx.md and docs/xxx/moduleMap.md". Do NOT output the full document content in the chat.**
