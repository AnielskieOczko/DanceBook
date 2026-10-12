-- Migration V44: Per-user assistant preferences (#309).
-- One row per user, created on first save; defaults used when absent.

CREATE TABLE user_assistant_preferences (
    user_id                  UUID        PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    assistant_enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    semantic_search_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    related_notes_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    suggest_figures_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    open_in_full_window      BOOLEAN     NOT NULL DEFAULT FALSE,
    reply_language           VARCHAR(10) NOT NULL DEFAULT 'APP',
    created_at               TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP   NOT NULL DEFAULT NOW()
);
