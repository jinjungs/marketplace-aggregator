Update both architecture.md (Korean) and architecture.en.md (English) to stay in sync.

Steps:
1. Read the current docs/architecture.md to understand what exists
2. Apply the requested changes to docs/architecture.md
3. Apply the exact same changes to docs/architecture.en.md, translating any new Korean content into English
4. Confirm both files are updated

Rules:
- docs/architecture.md is the source of truth — always edit it first
- docs/architecture.en.md must be a complete English translation; no section should exist in one file but not the other
- Preserve the existing structure: Overview, Diagram, Data Flow, AWS Services, Project Structure, DynamoDB Design, Endpoints, Auth, Idempotency, Retry Strategy, Cost Estimate, Q&A
- When adding a new Q&A entry, add it to both files in the same position
- Do not summarize or shorten existing content while updating

$ARGUMENTS
