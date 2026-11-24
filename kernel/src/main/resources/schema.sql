CREATE EXTENSION IF NOT EXISTS vector;

-- Source of truth: every fact, per goal in order. System facts (capability joined/left) have no goal.
CREATE TABLE IF NOT EXISTS events (
    seq          BIGSERIAL PRIMARY KEY,
    id           TEXT        NOT NULL UNIQUE,
    goal_id      TEXT,
    goal_seq     INT,
    type         TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    principal    TEXT,
    causation_id TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL,
    published    BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE (goal_id, goal_seq)
);
CREATE INDEX IF NOT EXISTS events_unpublished ON events (seq) WHERE NOT published;
CREATE INDEX IF NOT EXISTS events_goal ON events (goal_id, goal_seq);
-- The profile reads finished tasks and corrections by type on every turn.
CREATE INDEX IF NOT EXISTS events_type ON events (type, occurred_at);

-- Read model: latest folded state per goal (a cache; it can always be rebuilt from events).
CREATE TABLE IF NOT EXISTS goals (
    id         TEXT PRIMARY KEY,
    text       TEXT        NOT NULL,
    status     TEXT        NOT NULL,
    due_date   DATE,
    task_count INT         NOT NULL,
    done_count INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    INT         NOT NULL,
    snapshot   JSONB       NOT NULL
);

-- One row per plan run (composition plus execution of capability steps).
CREATE TABLE IF NOT EXISTS runs (
    run_id      TEXT PRIMARY KEY,
    goal_id     TEXT        NOT NULL,
    trigger     TEXT        NOT NULL,
    source      TEXT,
    outcome     TEXT,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    llm_calls   INT         NOT NULL DEFAULT 0,
    plan        JSONB,
    steps       JSONB       NOT NULL DEFAULT '[]'
);
CREATE INDEX IF NOT EXISTS runs_goal ON runs (goal_id, started_at);
-- Runs still open, which the coordinator sweeps every minute for runs that no worker carries any more.
CREATE INDEX IF NOT EXISTS runs_unfinished ON runs (started_at) WHERE finished_at IS NULL;

-- Every model call: audit trail and replay source (looked up by prompt hash).
CREATE TABLE IF NOT EXISTS decisions (
    id                TEXT PRIMARY KEY,
    goal_id           TEXT,
    run_id            TEXT,
    step              INT,
    capability        TEXT,
    instance_id       TEXT,
    purpose           TEXT,
    model             TEXT,
    prompt_hash       TEXT        NOT NULL,
    request           JSONB,
    response_text     TEXT,
    parsed            JSONB,
    valid             BOOLEAN     NOT NULL,
    errors            JSONB,
    attempt           INT         NOT NULL,
    latency_ms        BIGINT      NOT NULL,
    prompt_tokens     INT,
    completion_tokens INT,
    replayed          BOOLEAN     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS decisions_hash ON decisions (prompt_hash);
CREATE INDEX IF NOT EXISTS decisions_goal ON decisions (goal_id);

CREATE TABLE IF NOT EXISTS approvals (
    id          TEXT PRIMARY KEY,
    goal_id     TEXT        NOT NULL,
    run_id      TEXT        NOT NULL,
    step        INT         NOT NULL,
    capability  TEXT        NOT NULL,
    autonomy    TEXT        NOT NULL,
    reason      TEXT,
    status      TEXT        NOT NULL,
    decided_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    decided_at  TIMESTAMPTZ
);

-- Episodic memory: completed tasks with the model's own estimate (before the pace factor) and the actual effort,
-- searchable by meaning.
CREATE TABLE IF NOT EXISTS task_outcomes (
    task_id         TEXT PRIMARY KEY,
    goal_id         TEXT        NOT NULL,
    goal_text       TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    estimated_hours DOUBLE PRECISION,
    actual_hours    DOUBLE PRECISION,
    embedding       vector,
    embedding_model TEXT,
    completed_at    TIMESTAMPTZ NOT NULL,
    -- The kind of work the estimator named for the task (its estimate.calibration annotation), when it named one.
    kind            TEXT
);

-- Episodic memory: which capabilities processed which goal, and how it went.
CREATE TABLE IF NOT EXISTS episodes (
    run_id       TEXT PRIMARY KEY,
    goal_id      TEXT        NOT NULL,
    goal_text    TEXT        NOT NULL,
    capabilities TEXT        NOT NULL,
    task_count   INT         NOT NULL,
    outcome      TEXT        NOT NULL,
    llm_calls    INT         NOT NULL,
    duration_ms  BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS flags (
    name  TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Earned standing of each provider per capability: reliability, accuracy against real outcomes, acceptance.
CREATE TABLE IF NOT EXISTS reputation (
    capability    TEXT             NOT NULL,
    provider      TEXT             NOT NULL,
    calls         INT              NOT NULL DEFAULT 0,
    failures      INT              NOT NULL DEFAULT 0,
    outcomes      INT              NOT NULL DEFAULT 0,
    log_error_sum DOUBLE PRECISION NOT NULL DEFAULT 0,
    accepted      INT              NOT NULL DEFAULT 0,
    rejected      INT              NOT NULL DEFAULT 0,
    quarantined   BOOLEAN          NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (capability, provider)
);

-- Which effort report of a task (a change to done with hours) is counted against the estimate before it; a later
-- report for the same estimate replaces it.
CREATE TABLE IF NOT EXISTS reputation_scored (
    task_id   TEXT        NOT NULL,
    event_id  TEXT        NOT NULL,
    scored_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (task_id, event_id)
);

-- The person's constitution: rules compiled from plain words, enforced only after ratification.
CREATE TABLE IF NOT EXISTS rules (
    id         TEXT PRIMARY KEY,
    body       JSONB       NOT NULL,
    status     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- Messages the system starts: an observation plus a typed suggestion the person can accept with one yes.
CREATE TABLE IF NOT EXISTS inbox (
    id              TEXT PRIMARY KEY,
    goal_id         TEXT,
    kind            TEXT        NOT NULL,
    text            TEXT        NOT NULL,
    suggestions     JSONB       NOT NULL,
    suggestion_text TEXT,
    status          TEXT        NOT NULL,
    basis           TEXT,
    provider        TEXT,
    dedupe_key      TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    answered_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS inbox_dedupe ON inbox (dedupe_key);
CREATE INDEX IF NOT EXISTS inbox_status ON inbox (status, created_at);

-- What the person said about themselves; it overrides what the evidence suggests.
CREATE TABLE IF NOT EXISTS profile_overrides (
    key          TEXT PRIMARY KEY,
    value        JSONB       NOT NULL,
    by_principal TEXT,
    updated_at   TIMESTAMPTZ NOT NULL
);

-- Security: the owner's and guests' devices (by public key), agents asking for or holding admission, invitations,
-- and revoked grants. There are no passwords or sessions; a key and its signature are the identity.
CREATE TABLE IF NOT EXISTS devices (
    key_id     TEXT PRIMARY KEY,
    public_key TEXT        NOT NULL,
    role       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    grant_body TEXT,
    paired_at  TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS admissions (
    key_id       TEXT PRIMARY KEY,
    public_key   TEXT        NOT NULL,
    provider     TEXT        NOT NULL,
    capabilities JSONB       NOT NULL,
    produces     JSONB       NOT NULL,
    status       TEXT        NOT NULL,
    grant_body   TEXT,
    requested_at TIMESTAMPTZ NOT NULL,
    decided_at   TIMESTAMPTZ,
    decided_by   TEXT,
    -- When the agent last asked; a request nobody asked again for 7 days is dropped with its unread message.
    asked_at     TIMESTAMPTZ,
    -- The contract asked for last and the one the owner admitted: per capability what it reads, needs and writes,
    -- its side effects and autonomy. A row without them: the kernel records the contract such an agent asks for as
    -- admitted only when its grant covers it and it reads nothing beyond the blackboard's structure and what the
    -- grant lets it write, without side effects; otherwise the owner is asked.
    contract          JSONB,
    admitted_contract JSONB
);
CREATE TABLE IF NOT EXISTS invitations (
    id          TEXT PRIMARY KEY,
    secret_hash TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    goal_id     TEXT,
    actions     JSONB       NOT NULL,
    until       TIMESTAMPTZ,
    created_by  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    claimed_key TEXT,
    status      TEXT        NOT NULL
);
CREATE TABLE IF NOT EXISTS revoked_grants (
    id           TEXT PRIMARY KEY,
    revoked_at   TIMESTAMPTZ NOT NULL,
    by_principal TEXT
);
