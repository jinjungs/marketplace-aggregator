Update both architecture.md (English) and architecture.ko.md (Korean) to stay in sync.

Steps:
1. Read the current docs/architecture.md to understand what exists
2. Apply the requested changes to docs/architecture.md (English source of truth)
3. Apply the exact same changes to docs/architecture.ko.md, translating any new English content into Korean
4. Confirm both files are updated

Rules:
- docs/architecture.md (English) is the source of truth — always edit it first
- docs/architecture.ko.md must be a complete Korean translation; no section should exist in one file but not the other
- Preserve the existing structure: Overview, Diagram, Data Flow, AWS Services, Project Structure, DynamoDB Design, Endpoints, Auth, Idempotency, Retry Strategy, Cost Estimate, Q&A
- When adding a new Q&A entry, add it to both files in the same position
- Do not summarize or shorten existing content while updating

$ARGUMENTS
