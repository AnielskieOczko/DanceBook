-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Knowledge chunk table for hybrid search over notes, comments, figures, and choreographies
CREATE TABLE knowledge_chunk (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type     VARCHAR(32) NOT NULL,
    source_id       UUID NOT NULL,
    chunk_index     INT NOT NULL DEFAULT 0,
    content         TEXT NOT NULL,
    content_tsv     tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    embedding       vector(768),
    embedding_model VARCHAR(100) NOT NULL,
    owner_id        UUID REFERENCES app_user(id) ON DELETE SET NULL,
    visibility      VARCHAR(32) NOT NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Unique chunk per source and index
CREATE UNIQUE INDEX idx_knowledge_chunk_unique ON knowledge_chunk (source_type, source_id, chunk_index);

-- Source lookup index
CREATE INDEX idx_knowledge_chunk_source ON knowledge_chunk (source_type, source_id);

-- Owner and visibility index for access filtering
CREATE INDEX idx_knowledge_chunk_access ON knowledge_chunk (visibility, owner_id);

-- Full-text search GIN index on generated tsvector
CREATE INDEX idx_knowledge_chunk_tsv ON knowledge_chunk USING gin (content_tsv);

-- HNSW vector index for cosine distance similarity search
CREATE INDEX idx_knowledge_chunk_embedding ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
