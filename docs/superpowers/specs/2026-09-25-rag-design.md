# RAG over notes and figures — design

**Status:** approved in brainstorming as an idea, ready for an implementation plan once its
prerequisites land
**Depends on:** the assistant foundation (`2026-09-25-ai-assistant-design.md`, issue 1) and
sub-issue 1 of the access-control epic (`2026-09-25-access-control-epic-design.md`), so
visibility exists before anything is indexed.
**Infrastructure:** production Postgres is on **Neon**, which supports the `vector` extension,
including on the free tier. Local `compose.yaml` switches its image to one with pgvector
(`pgvector/pgvector:pg16`).

## Why

The assistant's v1 search tools match keywords, and on DanceBook's data that fails in three
specific ways:

1. **Paraphrase.** Searching "head dropping" misses a note that says "keep the left side of the
   neck long".
2. **Mixed Polish and English.** Notes are written in a mix of languages (see #144). Embeddings
   match meaning across languages, and `LIKE` cannot.
3. **Questions.** "What did the coach say about sway?" needs the relevant passages, and then an
   answer that cites them.

The corpus is small: hundreds to low thousands of chunks. A dedicated vector database would be
unnecessary infrastructure.

## Decisions

**Our own `knowledge_chunk` table, not Spring AI's `PgVectorStore`.** Retrieval must join
visibility (owner, public, shared) and combine vector similarity with Postgres full-text search
in one query. Spring AI's store owns its schema and does vector-only search. We use Spring AI
for the **`EmbeddingModel`** (Google GenAI, using the same `GOOGLE_AI_API_KEY`), and write the
retrieval SQL ourselves.

**Hybrid retrieval.** A vector top-k and a full-text top-k (`tsvector`, `simple` config because
of the mixed languages) are merged by reciprocal rank fusion. Exact figure names such as
"Hockey Stick" therefore still rank first.

**The model name is stored with every chunk.** Changing the embedding model triggers a full
reindex, rather than mixing vectors from two different models.

## Data model

`knowledge_chunk`:
- `id`
- `source_type`: `NOTE`, `NOTE_COMMENT`, `FIGURE` or `CHOREOGRAPHY`
- `source_id`, `chunk_index`
- `content` (the text that was embedded), `content_tsv` (generated `tsvector`)
- `embedding` (`vector(N)`, where N is the configured dimension)
- `embedding_model`
- `owner_id`, `visibility`, copied from the source, so the access rule filters here without
  joining back to the source
- `updated_at`

Indexes: HNSW on `embedding`, and GIN on `content_tsv`.

**What a chunk contains:**
- **Note:** title, dance, pinned figure names with their timing, and the text as plain text
  (sanitised HTML stripped). A long note is split by paragraph at about 1,500 characters.
- **Comment:** the note's title plus the comment.
- **Figure:** name, dance, class, timing, notes, starting and ending positions, and a summary
  of the steps.
- **Choreography:** name, dance, description, and the figure names in order.

## Keeping the index current

- An `@TransactionalEventListener(AFTER_COMMIT)` on the existing domain events (create, update,
  delete, visibility change) queues the source for re-embedding. A small `@Async` worker
  processes the queue, so a save never waits for the embedding call.
- Deletes remove the chunks straight away. A visibility change updates `visibility` straight
  away, without re-embedding, so an item made private disappears from search at once.
- An admin action **Rebuild knowledge index** backfills everything, and also runs after a model
  change. It is batched and rate-limited for the free tier.
- If an embedding call fails, the source stays queued and is retried with backoff. Search keeps
  working on the full-text half until it succeeds.

## Where it is used

1. **The assistant's `search_notes` and `search_figures`** switch to hybrid retrieval. Their
   arguments and result shapes don't change.
2. **A new assistant tool, `search_knowledge(question)`,** returns passages with their source.
   The system prompt requires answers built from these passages to **cite them**, and each
   citation renders as a link to the note or figure. With no relevant passage, the assistant
   says it found nothing in the user's notes rather than answering from general knowledge.
3. **Related notes** on the note page and the figure page: the top 3 visible chunks by vector
   similarity to the current item, excluding the item itself.

## Testing

- **Access:** user B's retrieval never returns user A's private note, including right after A
  makes a public note private.
- **Hybrid ranking:** an exact figure-name match ranks above a vaguely similar note.
- **Index freshness:** a create, an update and a delete each change the chunks through the
  event listener (with a stubbed `EmbeddingModel`).
- **Reindex:** a model change marks everything stale, and the rebuild replaces it.
- Integration tests against a pgvector Testcontainer.
- `./gradlew build` is green.

## Out of scope

- Indexing video content or transcripts.
- Re-ranking with a second model.
- Using retrieval in #145's figure suggestions. That can adopt it later.
