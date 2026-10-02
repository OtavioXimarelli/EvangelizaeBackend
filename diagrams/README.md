# Diagrams — Evangelizae Visual Architecture

All diagrams are **MongoDB only + Redis** and pair with `../ARCHITECTURE.md` (spine) and `../SYSTEM_DESIGN.md`.

| File | Diagram | Type |
|------|---------|------|
| `01-system-context.mmd` | System Context (C4 L1) | C4Context |
| `02-containers.mmd` | Containers (C4 L2) | C4Container |
| `03-backend-modules.mmd` | Backend Modules (Package-by-Feature) | graph TB |
| `04-hexagonal-slice.mmd` | Hexagonal Slice (single Mongo paradigm) | graph LR |
| `05-ingestion-sequence.mmd` | Ingestion Sequence (External → Mongo) | sequenceDiagram |
| `06-public-reads.mmd` | Public Reads (liturgy + social) | sequenceDiagram |
| `07-data-model.mmd` | Data Model (ER — MongoDB) | erDiagram |
| `08-deployment.mmd` | Deployment | graph TB |
| `09-failure-idempotency.mmd` | Failure & Idempotency | stateDiagram-v2 |

## Usage

- **GitHub:** any `.mmd` renders if pasted into a markdown ` ```mermaid ` block.
- **mermaid.live:** paste file content → Export SVG/PNG/PDF.
- **VS Code:** `Markdown Preview Mermaid Support` extension.
- **CLI:** `npx @mermaid-js/mermaid-cli -i 01-system-context.mmd -o 01-system-context.svg`

All 9 diagrams are also embedded in `../VISUAL_ARCHITECTURE.md:1` for single-file viewing.
