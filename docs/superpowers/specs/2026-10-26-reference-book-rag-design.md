# Reference book search (Moore, *Ballroom Dancing*) — design

**Status:** Parked, not scheduled. Written 2026-10-26 so the idea is not lost.

## Intent

Digitize Moore's *Ballroom Dancing* and make it searchable by meaning inside DanceBook, so a
dancer working on a figure or a note can pull up what the book says about it. It is also a
practical exercise in building retrieval-augmented generation (RAG) end to end.

DanceBook already has a hybrid search stack, so this feature extends that stack. It does not
add a new one.

## Decisions made

- **Source format:** scans, so the pages need OCR before anything else.
- **Audience:** every logged-in user can search the book, not only the owner.
- **Copyright mitigation:** citations. Every excerpt carries a fixed footnote (przypis), and
  the assistant answers in its own words with numbered footnote markers.
  - Footnote format: `Moore, A. (<edition year>), *Ballroom Dancing*, ch. <chapter>, p. <page>`.
    The author initial and edition details still have to be confirmed against the copy that is
    scanned.
  - Excerpts are short (configurable cap, default about 300 characters). The book can never
    be dumped whole.
  - The endpoint is rate-limited per user.
  - A citation is attribution, not a licence. It lowers the exposure and does not remove it;
    the remaining copyright risk is a decision for the owner of the project.

## Goals

- Search the book by meaning and by keyword from inside DanceBook.
- Show where the book discusses a given figure, with a page citation.
- Let the assistant answer questions from the book and cite the pages it used.
- Produce a retrieval evaluation (recall@5 on a fixed question set) to learn from.

## Non-goals

- Publishing the book text, or committing it to the repository.
- A reader for browsing the book page by page.
- A seventh navigation item. The navigation is fixed at six.
- Support for more than one book in the first version, although the tables allow it.

## Anti-goals

- Anything that lets a user retrieve the book in bulk.
- An assistant answer without a citation to a passage it was given.
- Keyword-only results reported as "nothing found by meaning" when embeddings are off.

## Architecture

1. **OCR (offline, outside the app and outside git).** A script in `tools/book-ocr/` sends page
   images to Gemini vision and writes one markdown file per page, with front matter (`page`,
   `chapter`), into a gitignored local folder. The output is reviewed by hand for figure
   diagrams, step tables, hyphenation and running headers.
2. **Storage (Flyway V51).**
   - `reference_book(id, title, author, edition_year)`.
   - `reference_passage(id, book_id, chapter, page_from, page_to, text, position)` is the source
     of truth that citations come from.
   - Chunks go into the existing `knowledge_chunk` table with `source_type = REFERENCE_PASSAGE`,
     `source_id` = the passage id and `visibility = PUBLIC`. Check whether `owner_id` is
     nullable; if not, relax it in the same migration.
3. **Import and chunking.** An admin-only import service reads the page folder, merges pages
   into passages by chapter and heading (about 250–400 tokens, with a little overlap) and
   stores them. It publishes a domain event so the existing index listener and worker embed
   them. It never calls the embedder in the request thread and never holds a transaction across
   an embedding call.
   - Rough cost: about 300 pages gives about 1,000 chunks. At 90 embeddings a minute that is
     about 12 minutes of worker time. To be checked against the free-tier quota.
4. **Retrieval and surfacing.** Add a source-type filter to `HybridSearchService`. Use the
   existing cutoffs (0.42 for search boxes, 0.65 for assistant tools) until evaluation says
   otherwise. The book appears in three places:
   - an "In Moore's book" group on the figure detail page, queried by figure name;
   - a book group under "Also related" on the Notes and Figures search;
   - a new assistant tool `search_reference_book`, returning passages with their citations.
   - An admin or profile row covers import and status.
   - If embeddings fail or the key is missing, the feature falls back to keyword results with
     no banner, as the current search does.

## What already exists and should be reused

- `knowledge_chunk` (V39) with the full-text index, the HNSW vector index and
  `KnowledgeChunkRepository.accessCondition`.
- `HybridSearchService`, `KnowledgeIndexService`, `KnowledgeIndexEventListener`,
  `EmbeddingBudget`, and the admin rebuild at `/admin/knowledge/rebuild`.
- `AssistantReadTools` (`search_knowledge`) and `@ConditionalOnAssistant`.
- The "Also related" group (#233, #234), `fragments/page` and `settingsRow`.
- `./gradlew evaluateSemanticSearch` and `docs/evaluation/semantic-search/`.

## Phases

Each phase leaves something usable.

1. **Admin-searchable book.** OCR script and cleaned text, the V51 migration, import service,
   index wiring, an admin status row and an admin-only search view to check retrieval quality,
   and an evaluation set of 30–50 questions with expected pages.
2. **Surfaced to users.** Mock first in `docs/mocks/`, then the figure-detail group, the
   "Also related" group and the assistant tool, with footnotes everywhere. Strings need keys in
   `messages.properties` and `messages_pl.properties` and must fit at 375px.

## Tests and verification

- Unit: chunker (chapter boundaries, overlap, page mapping) and import (re-import is idempotent,
  no duplicate chunks).
- A migration test in the style of `TrainingRecordBackfillTest`.
- Integration with Testcontainers on the pgvector image: the right passage comes back, and the
  access condition holds for a null owner with public visibility.
- Existing guards: `WebRouteSmokeTest` fixtures for new routes, `FragmentCatalogRenderingTest`,
  `HtmxFragmentRenderingTest`, `.with(csrf())` on POSTs, `@MockitoBean`.
- Retrieval quality: recall@5 from the evaluation harness, recorded before and after
  threshold tuning.
- Manual: run the app, open a figure and check the book group and its footnotes, ask the
  assistant a question and check the cited page, then unset the key and check the keyword-only
  fallback.

## Open items

- The exact footnote format and the edition details of the scanned copy.
- Where the scans live and how many pages there are (OCR cost and import size).
- Whether to start Phase 1 with a 20-page sample to tune chunking before the full book.
- The excerpt cap and the per-user rate limit.
- Whether `knowledge_chunk.owner_id` can be null.
