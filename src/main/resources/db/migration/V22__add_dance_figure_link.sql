-- Create table for storing multiple training and reference URLs per dance figure
CREATE TABLE dance_figure_link (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dance_figure_id  UUID NOT NULL REFERENCES dance_figure(id) ON DELETE CASCADE,
    url              VARCHAR(512) NOT NULL,
    title            VARCHAR(255),
    type             VARCHAR(50)
);

-- Index to optimize querying links for a specific figure
CREATE INDEX idx_dance_figure_link_figure ON dance_figure_link(dance_figure_id);
