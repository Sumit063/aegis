-- Partner requests (M4, LLD §14): one row per partner and request ID. The primary key is what makes a
-- request ID execute at most once, whichever instance receives it. Bodies are sealed (enc:v1:…).
CREATE TABLE partner_requests (
    partner      text        NOT NULL,
    request_id   text        NOT NULL,
    request_hash text        NOT NULL,          -- hex SHA-256 of method, path and body
    method       text        NOT NULL,
    path         text        NOT NULL,          -- below /partner, without the query (kept in clear)
    state        text        NOT NULL CHECK (state IN ('IN_PROGRESS', 'ANSWERED', 'UNKNOWN')),
    request      text        NOT NULL,          -- sealed body
    http_status  int,                           -- the application's answer
    content_type text,
    response     text,                          -- sealed answer
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (partner, request_id)
);
CREATE INDEX partner_requests_by_activity ON partner_requests (updated_at);
