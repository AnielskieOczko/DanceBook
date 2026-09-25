# AI assistant — design

**Status:** approved in brainstorming, ready for an implementation plan
**Mocks:** https://claude.ai/artifact/7HhkTzZwbQMx2qknJVrhUY. The chat bar is in `Mix-Mobile`
(docked above the tab bar) and `Mix-Desktop-Collapsible` (floating at the bottom). The
conversation and draft-card look is in `Assist-A` (note draft), `Assist-B` (search results)
and `Assist-C` (session draft).
**Ships as two issues:** (1) foundation and search, then (2) drafts. Issue 2 depends on #142.
**Followed by:** RAG (`2026-09-25-rag-design.md`), which replaces the keyword search behind the
same tools.

## Goal

One assistant, reachable from every page, that lets the user **capture and find knowledge by
talking**. For example:
- "add a note: Tuesday's class, feather step, head dropping, mark it attended"
- "add practice Saturday 10–12, quickstep then tango"
- "help me find figures with a heel turn"
- "which notes talk about sway on the natural turn?"

## Decisions

**Spring AI, not an extension of our own `LlmProvider`.** Spring AI's `ChatClient` provides
tool calling that works across providers (Google GenAI with an API key, OpenAI-compatible APIs
such as OpenRouter, Ollama, Anthropic). RAG will also need its embedding models and PgVector
store. Hand-writing each provider's function-calling format is exactly the work that
per-request model choice would multiply. The existing `LlmProvider` / `LlmProviderRouter` stay
in place for syllabus import, #144 and #145. Migrating them is out of scope.

**Gemini first, provider choice later.** v1 configures the Google GenAI chat model with the
existing `GOOGLE_AI_API_KEY`. Keep everything behind Spring AI's `ChatModel`, so adding
OpenRouter, Ollama or Anthropic, and a per-request model picker, is configuration plus a
settings field, not a rewrite.

**The assistant never writes on its own.** Read tools run immediately. Write intents produce a
**draft**: a card with the same fields as the real form. Only the user's **Save** runs the
write, and it goes through the existing services, so activity events, `TrainingRecord` writes
and the Google Calendar mirror behave exactly as they do from the forms. **Edit in form** opens
the normal create form prefilled with the draft.

**Conversations are saved, and private to their owner.** Every query is scoped to the
logged-in user. Another user's conversation id returns **404**, not 403, so it doesn't reveal
that the conversation exists. We use our own tables rather than Spring AI's
`JdbcChatMemoryRepository`: that repository has no concept of an owner, and drafts need to be
stored with a status.

**No streaming in v1.** A typing indicator, then the rendered reply comes back as an HTMX
fragment.

**Tools go through the services, never the repositories**, so the access rules (see the
access-control epic) apply to the assistant automatically once they exist.

## Data model (Flyway)

- `assistant_conversation`:
  - `id`
  - `owner_id` (FK `app_user`, not null)
  - `title` (from the first user message, max 80 chars, and renameable)
  - `created_at`, `updated_at`
- `assistant_message`:
  - `id`, `conversation_id` (FK, cascade on delete)
  - `role`: `USER`, `ASSISTANT` or `TOOL`
  - `content` (text)
  - `tool_payload` (jsonb, nullable: the tool name, arguments and result)
  - `created_at`
- `assistant_draft`:
  - `id`, `message_id` (FK)
  - `kind`: `NOTE`, `TRAINING_EVENT` or `FIGURE`
  - `payload` (jsonb: the validated request DTO)
  - `status`: `PENDING`, `SAVED` or `DISCARDED`
  - `saved_entity_id` (nullable)
  - `created_at`

  **A draft can be saved at most once.** Saving checks and moves the status in the same
  transaction.

## The loop

`AssistantService.send(conversationId?, text, pageContext)`:

1. Resolves or creates the conversation (owned by the current user), and stores the user
   message.
2. Builds the prompt:
   - the system prompt: DanceBook's domain in a few lines, today's date, the user's display
     name, the **page context** (for example "the user is looking at figure *Natural Turn*
     (id …)"), and the rule that writes are only ever drafts
   - the last 20 messages of the conversation
   - the tool list
3. Calls `ChatClient`, and Spring AI runs the tool calls. It stops after **5 tool rounds**, and
   then answers with what it has.
4. Stores the assistant message, the tool messages and any drafts, and returns the rendered
   messages.

**Page context** is sent by the widget on each message: `{type: HOME|NOTE|FIGURE|SESSION|
CHOREOGRAPHY|OTHER, id?}`. The server resolves the id itself, through the service, before
putting its name in the prompt, and never trusts a name sent by the client.

## Tools

**Read tools** (issue 1) run immediately. Results are capped at 10 and returned as compact
JSON, which the reply renders as result cards that link to the real pages.

| Tool | Arguments | Backed by |
|---|---|---|
| `search_figures` | `query`, `danceType?`, `danceClass?` | the catalog's existing filter (the one the figures list uses) |
| `search_notes` | `query`, `figureId?`, `danceType?` | a text match on note title and text, plus notes pinning `figureId` |
| `list_sessions` | `from`, `to`, `unconfirmedOnly?` | the training event service (the same scoping as the agenda) |
| `get_note` / `get_figure` | `id` | the services' `findById` |

**Draft tools** (issue 2) create an `assistant_draft` and return its id and a summary.

| Tool | Validated against | Save calls |
|---|---|---|
| `draft_note` | `MaterialRequest` plus pinned figure ids, `sessionId?`, `markAttended?` | `MaterialService.create`, #142's pin, the session link, and the attendance update if `markAttended` |
| `draft_training_event` | the training event request DTO, including segments and a linked note | `TrainingEventService.create` (and therefore the Google Calendar mirror) |
| `draft_figure` | `DanceFigureRequest` | `DanceFigureService.create` |

**Grounding rule, as in #145:** every figure, note and session id in a draft must have come
from a tool result in this conversation, and any other id is dropped before the draft is
stored. Drafts are validated with the same Bean Validation as the forms. When validation fails,
the model gets the error back and can retry once. If that fails too, it asks the user.

## UI

- **Phone.** The chat bar sits docked above the four-item tab bar on the home page, with two
  or three suggestion chips (for example "Wrap up Tuesday's class", which appears only when
  something is waiting). On other pages, an assistant button in the header opens the same
  chat. Tapping the bar opens a **full-height sheet** with the conversation, and its header
  shows "Looking at: <page>".
- **Desktop.** The chat bar floats at the bottom centre of the content. **`/` focuses it**
  (except inside text inputs), and an **Assistant** button in the page header does the same.
  Sending a message slides in a **right-hand panel** (about 400px) holding the conversation.
  The panel can be closed; the dashboard never reserves that width.
- **History.** The panel or sheet has a History list: the user's conversations, newest first,
  which can be continued, renamed or deleted. **New conversation** starts a fresh one.
- **Draft cards.** They render every field (for a note: title, text, session, dance, **each
  figure with its timing**), plus **Save** and **Edit in form**. A saved draft turns into a
  link to the created item. A discarded draft greys out.
- **Mic.** The browser's Web Speech API fills the input. The button is hidden when the API is
  unavailable. Nothing is sent to a server except the text.
- **Availability.** The whole assistant (bar, button, routes) is hidden and returns 404 when no
  chat model is configured.

Icons built in JavaScript use `renderIcon`. Markup uses the fragment catalog, and new fragments
go into `FragmentCatalogRenderingTest`.

## Errors

- **Provider failure or timeout** (30s): the assistant message reads "The assistant couldn't
  answer just now. Try again." The user message is kept. Nothing else changes.
- **Save failure** (validation, calendar sync): the draft stays `PENDING` and the card shows the
  error from `fragments/alert`.
- **Rate limit.** At most 20 messages per user per minute, with a readable refusal.

## Testing

All with a stubbed `ChatModel` that scripts tool calls:
- **Ownership:** user B cannot list, open, continue, rename or delete user A's conversation
  (404), and cannot save A's draft.
- The loop stops after 5 tool rounds.
- An id the model invents is dropped from a draft.
- A draft saves once: a second Save is refused. Save calls the same service method as the form
  and publishes its domain event.
- `draft_note` with `markAttended` writes the `TrainingRecord`.
- The assistant is hidden and its routes return 404 without a configured model.
- A controller test covers the HTMX send → fragment round trip.
- `./gradlew build` is green.

## Issue split

1. **Assistant: foundation and search.** Spring AI and Gemini, the tables, the loop, page
   context, read tools, chat UI (bar, sheet, panel, `/`, mic, history), errors and availability.
2. **Assistant: drafts.** The three draft tools, draft cards, Save and Edit in form, and the
   wrap-up flow. Depends on #142.

## Out of scope

- Streaming, a model picker in the UI, and migrating the old `LlmProvider` users.
- Semantic search and answers with citations (the RAG spec).
- Editing or deleting existing items through the assistant.
