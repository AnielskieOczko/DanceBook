# Semantic search evaluation set (#331)

Synthetic data and example searches for tuning the "found by meaning" results in Notes and
Figures search and the assistant's retrieval. The data is **invented** (the dev database had
figures with almost no detail and a handful of short, artificial notes), so use it to find
*why* weak matches get through, and confirm any tuning against real notes before trusting it.

## Data

- `seed.sql` adds, for the dev database only: 28 Notes (public and private, of varied length,
  including a handful of off-topic ones such as shoe care and tempo tables), 17 comments,
  technique notes and start/end positions for 40 figures, and step tables for 6 figures
  (Waltz Reverse Turn and Whisk, Tango Closed Promenade, Foxtrot Feather Step, Cha Cha
  Hockey Stick, Rumba Fan). Two Notes belong to `test_user` and are private.
- `revert.sql` removes exactly that data. Both scripts are generated and idempotent where it
  matters (fixed ids, `ON CONFLICT DO NOTHING`, figures that already have notes are left alone).
- After running either script, **rebuild the knowledge index** (Admin dashboard, "Rebuild").
  The scripts write straight to the database, so nothing is queued for embedding by itself.

## Example searches

Signed in as `rafal` (an Administrator) unless a row says otherwise. "Expect" is a proposal; the owner confirms or corrects it in the last
column before any tuning starts (relevance is the owner's call).

| # | Search | Kind | Expected good results | Expected wrong results | Owner verdict |
|---|--------|------|-----------------------|------------------------|---------------|
| 1 | `head dropping` | Note worded differently | Notes: Dropping the head in the lowering; Waltz keep the head weight left; Tango head snap; Foxtrot head and topline | Shoe care, competition checklist, music tempo | |
| 2 | `keep the left side long` | Note worded differently | Notes: Left side long (lady's frame); Waltz head weight left; Quickstep speed control | Samba/Jive notes, shoe care | |
| 3 | `Natural Turn` | Figure name | Figures named Natural Turn (Waltz, Foxtrot, Viennese Waltz) | Unrelated figures from other dances | |
| 4 | `Latin` | Style word | (open) Latin notes and figures, or none | Standard-only notes | |
| 5 | `hips` | Very short query | Cha Cha hip action; Rumba walks; Samba bounce | Waltz, Foxtrot, Tango notes | |
| 6 | `rise` | Very short query | Waltz rise and fall; Hesitation change; Viennese Waltz | Tango (no rise and fall: judge) | |
| 7 | `swimming pool opening hours` | Nothing relevant | **No related group at all** | Everything | |
| 8 | `nervous before a competition` | Note worded differently | Performing under pressure; Competition day checklist | Technique notes | |
| 9 | `stalking cat walk` | Image from a Note | Tango staccato action; Tango walks | Waltz, Rumba | |
| 10 | `head position` as `test_user` (not an admin) | Access rule | Own and public Notes only | `rafal`'s private Notes ("Private — weak points", "Choreography idea: Waltz short routine") must **never** appear. Note: Administrators see every Note by design, so rafal sees `test_user`'s private ones | |

## How to record a run

For each search: the candidates with their distance, how many were kept and why, before and
after the change. Run the set once per change and keep the scores; embedding calls are rate
limited, so do not re-embed per tweak.

## Findings (#331)

Run on the dev database after seeding, model `gemini-embedding-001` (768 dimensions), no query or
document task type. Distances are cosine, from the debug log of `HybridSearchServiceImpl` with
the cutoff widened to 1.0 so every one of the 30 candidates was visible. The "Owner verdict"
column above has **not** been judged by the owner yet; the good and weak labels here are the
author's.

| Search | Nearest | 10th | 30th | Note |
|---|---|---|---|---|
| `head dropping` | 0.229 | 0.395 | 0.456 | right notes first, noise from about 0.39 |
| `keep the left side long` | 0.286 | 0.418 | 0.441 | the exact note first, then weak items |
| `Natural Turn` | 0.237 | 0.301 | 0.380 | all figures of that name |
| `nervous before a competition` | 0.288 | 0.434 | 0.462 | |
| `head position` | 0.309 | 0.383 | 0.468 | |
| `stalking cat walk` | 0.348 | 0.427 | 0.445 | |
| `hips` | 0.368 | 0.401 | 0.452 | |
| `Latin` | 0.433 | 0.459 | 0.470 | nothing relevant |
| `rise` | 0.436 | 0.465 | 0.492 | |
| `swimming pool opening hours` | 0.511 | 0.522 | 0.531 | nothing relevant |

- The old cutoff of 0.65 never dropped anything: the 30th candidate was never past 0.531.
- A query with real answers has its nearest match at 0.23 to 0.37; the two queries with nothing
  relevant start at 0.433 and 0.511. A cutoff of 0.42 (now the default) separates them.
- Good and weak matches overlap between about 0.39 and 0.42 (for example "Tango head snap" at
  0.390 and the figure "Drop (or Tilt) Oversway" at 0.394), so a single number cannot remove
  the weak ones. A short query such as `rise` sits farther from everything.
- Before and after the change on the live app: `Latin`, `rise` and `swimming pool opening
  hours` went from 2 to 5 unrelated results to none; the other searches with real answers lost
  nothing. The first baseline run was partly unreliable (a few searches returned nothing, most
  likely the per-user embedding limit); the second run used 30 seconds between searches.
- Separate bug (#346): a note that holds every typed word is marked as found by keyword, and the
  Notes page's own list does not show it, so it appears nowhere. It hid the nearest match in
  `head dropping`, `keep the left side long` and `stalking cat walk`.
- Not evaluated: the assistant's retrieval (it keeps the repository default of 0.65), and other
  embedding models or query/document task types (#347).
- The figures in this data are mostly name, dance and class only, which also makes them close to
  each other (nearest other figure at a median 0.076 for the plain ones). Richer figure text may
  help more than any cutoff.

To re-run: seed (`seed.sql`), rebuild the knowledge index from the Admin dashboard, start the app
with `--logging.level.com.jankowski.rafal.dancebook.service.HybridSearchServiceImpl=DEBUG`
and `--google.ai.semantic-search-max-distance=1.0`, then run each search and read the
"Semantic candidates" lines. Allow about 14 seconds between searches: the search box embeds at
most 5 queries per user per minute and silently skips semantic search beyond that.

## How to run the comparison

The automated evaluation tool (#347) runs the comparison across candidate embedding models and task types with a single command:

```bash
GOOGLE_AI_API_KEY=your_key ./gradlew evaluateSemanticSearch
```

Or with `GOOGLE_AI_API_KEY` exported in your environment:
```bash
export GOOGLE_AI_API_KEY="AIza..."
./gradlew evaluateSemanticSearch
```

### How it works

1. **Candidates evaluated:**
   - `gemini-embedding-001` (current baseline: 768 dimensions, symmetric / no task type)
   - `gemini-embedding-001-task-types` (`gemini-embedding-001`, 768 dimensions, with `RETRIEVAL_QUERY` and `RETRIEVAL_DOCUMENT` task types)
   - `gemini-embedding-2-preview` (Gemini Embedding 2: 3072 dimensions, multimodal/text preview)
   - `embedding-gemma` (EmbeddingGemma: 768 dimensions, via Ollama Cloud if configured with `OLLAMA_API_KEY`, otherwise marked "not tried")
2. **Label file:** The search queries and good/bad labels live in `docs/evaluation/semantic-search/labels.json`. It is pre-filled from the table above and marked unconfirmed until the owner edits and confirms it.
3. **Vector persistence:** Vectors are saved per candidate in `docs/evaluation/semantic-search/vectors-<candidate>.json`, which git ignores (they are per machine and large). Re-running the tool or editing labels makes no new embedding calls.
4. **Rate limiting:** API calls are throttled to respect the app's rate limit of 90 requests/minute.
5. **Read-only on database:** The tool never writes to `knowledge_chunk` or modifies the app's knowledge index. The main index remains completely untouched.
6. **Graceful fallbacks:** The API key comes from the environment and is never printed or logged. If a key is missing or a candidate cannot be reached, it is reported as `not tried: <reason>` rather than failing the run.
7. **Report output:** Writes a detailed Markdown report to `docs/evaluation/semantic-search/REPORT.md` with candidate comparison summaries, nearest/10th/30th distances, good/bad separation gaps, suggested cutoffs, and ranked hits for all 10 searches.

### What switching models would force

If the report recommends switching the embedding model (or if vector dimensions change), the following architectural changes are required:

1. **Re-embedding everything:** Every note, comment, figure, and choreography in the database must be re-embedded with the new model.
2. **Flyway database migration:** The database column `knowledge_chunk.embedding` is defined as `vector(768)` in `V36__knowledge_index.sql`. If switching to a model with different dimensions (e.g., Gemini Embedding 2 with 3072 dimensions), a new Flyway migration (`V<next>__...sql`) is required to alter or recreate the column with the new dimension size.
3. **Model-change stale index check:** As described in `AGENTS.md`, each chunk row persists `embedding_model`. `KnowledgeIndexService.isStale` compares `chunk.embeddingModel` against `googleAi.embeddingModel`. Updating the configured model name automatically marks the index stale and triggers a full rebuild.
