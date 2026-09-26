-- Opaque, rotating refresh tokens for non-browser (mobile) clients. The web
-- client continues to rely solely on the httpOnly SameSite=Strict access-token
-- cookie and never uses this table; a mobile client stores the raw token
-- client-side and exchanges it at /api/v1/auth/refresh for a new access token
-- + a new refresh token (rotation), so a leaked/replayed token is detectable
-- (an already-revoked token being presented again revokes the whole chain).
--
-- No tenant_id: tenancy was removed in V18__remove_multi_tenancy.sql and this
-- table only ever follows users(id), same as V18 left every other auth-path
-- table. Grants for the restricted app_user role are already covered by the
-- ALTER DEFAULT PRIVILEGES set up in V4__restricted_app_runtime_role.sql.

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_token_id UUID,
    created_by_user_agent VARCHAR(255),
    created_by_ip VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX refresh_tokens_token_hash_key ON refresh_tokens(token_hash);
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens(user_id);
