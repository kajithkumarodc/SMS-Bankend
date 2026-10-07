-- Academics -> Sections: the master list of section names (A, B, C ...) that classes pick from.

CREATE TABLE section_names (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT section_names_name_key UNIQUE (name)
);

-- Every section name already in use by a class becomes a list entry.
INSERT INTO section_names (id, name)
SELECT gen_random_uuid(), name FROM (SELECT DISTINCT name FROM sections WHERE is_default = false) used;
