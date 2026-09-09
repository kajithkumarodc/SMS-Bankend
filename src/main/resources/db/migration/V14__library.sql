-- Library -- first slice (plan section 2, "Library: catalog ..., issue/return,
-- reservations, fines, barcode support"). This slice is catalog + issue/return
-- only; reservations, fines and barcodes come later.
--
-- Both tables are tenant-scoped and follow the existing RLS pattern
-- (ENABLE + FORCE RLS, one FOR ALL policy with matching USING + WITH CHECK).
-- With no tenant context set the policy matches zero rows (fail-safe).

CREATE TABLE library_books (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    title VARCHAR(300) NOT NULL,
    author VARCHAR(200) NOT NULL,
    isbn VARCHAR(20),
    total_copies INTEGER NOT NULL,
    -- Copies not currently on loan. Kept in step with book_loans by the service
    -- on every issue (-1) and return (+1).
    available_copies INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT library_books_total_copies_positive_check CHECK (total_copies > 0),
    CONSTRAINT library_books_available_copies_range_check
        CHECK (available_copies >= 0 AND available_copies <= total_copies)
);

CREATE TABLE book_loans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    book_id UUID NOT NULL REFERENCES library_books(id),
    student_id UUID NOT NULL REFERENCES students(id),
    issued_date DATE NOT NULL,
    due_date DATE NOT NULL,
    -- Null while the book is still out; set on return.
    returned_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- `tenant_id` as the leading index column everywhere (plan section 1/4).
CREATE INDEX library_books_tenant_id_idx ON library_books(tenant_id);
CREATE INDEX library_books_tenant_title_idx ON library_books(tenant_id, title);
CREATE INDEX book_loans_tenant_id_idx ON book_loans(tenant_id);
CREATE INDEX book_loans_tenant_student_id_idx ON book_loans(tenant_id, student_id);

ALTER TABLE library_books ENABLE ROW LEVEL SECURITY;
ALTER TABLE library_books FORCE ROW LEVEL SECURITY;
ALTER TABLE book_loans ENABLE ROW LEVEL SECURITY;
ALTER TABLE book_loans FORCE ROW LEVEL SECURITY;

CREATE POLICY library_books_current_tenant_policy ON library_books
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY book_loans_current_tenant_policy ON book_loans
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
