# playlet-generator Guide

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
- **`playlet-api/src/main/java/org/playlet/api/web`**: Provides RESTful interfaces.
- **`playlet-comfyui/src/main/java/org/playlet/comfyui/backend/video/MiniMaxH3Backend.java`**: Core class for video generation.
- **`playlet-comfyui/src/main/java/org/playlet/comfyui/backend/image/AnimaImageBackend.java`**: Core class for image generation.
- **`playlet-comfyui/src/main/java/org/playlet/comfyui/template/WorkflowImporter.java`**: Core class for importing and parsing ComfyUI workflows.

## BUILDING
* You must read `docs/dev/BUILDING.md` before building or running. Do not guess build commands.
