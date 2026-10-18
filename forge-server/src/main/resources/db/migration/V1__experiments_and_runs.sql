-- FORGE platform schema. Experiment results are stored as jsonb so scenario output can evolve
-- without migrations; everything the platform queries on is a typed column.

create table experiment (
    id                        uuid primary key,
    title                     text        not null check (length(title) between 1 and 200),
    question                  text        not null default '',
    hypothesis                text        not null default '',
    hypothesis_updated_at     timestamptz not null default now(),
    scenario_id               text        not null,
    status                    text        not null default 'OPEN'
                                          check (status in ('OPEN', 'CONCLUDED', 'ARCHIVED')),
    observations              text        not null default '',
    alternative_explanations  text        not null default '',
    decision                  text        not null default '',
    follow_ups                text        not null default '',
    review_answers            jsonb       not null default '{}'::jsonb,
    tags                      jsonb       not null default '[]'::jsonb,
    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now()
);

create index experiment_updated_idx on experiment (updated_at desc);

create table run (
    id                uuid primary key,
    experiment_id     uuid        not null references experiment (id) on delete cascade,
    seq               int         not null,
    label             text        not null default '',
    role              text        not null check (role in ('BASELINE', 'CANDIDATE', 'EXPLORATORY')),
    scenario_id       text        not null,
    scenario_version  int         not null,
    variant           text        not null,
    params            jsonb       not null,
    seed              bigint      not null,
    trials            int         not null check (trials > 0),
    warmup_trials     int         not null check (warmup_trials >= 0),
    heap_mb           int         not null,
    timeout_seconds   int         not null,
    status            text        not null
                                  check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    status_reason     text,
    exit_code         int,
    progress_done     bigint,
    progress_total    bigint,
    progress_phase    text,
    environment       jsonb,
    result            jsonb,
    forge_version     text        not null,
    cancel_requested  boolean     not null default false,
    queued_at         timestamptz not null default now(),
    started_at        timestamptz,
    finished_at       timestamptz,
    unique (experiment_id, seq)
);

create index run_experiment_idx on run (experiment_id, seq);
-- the queue only ever scans the small set of unfinished runs
create index run_pending_idx on run (queued_at) where status in ('QUEUED', 'RUNNING');

create table run_event (
    id       bigserial primary key,
    run_id   uuid        not null references run (id) on delete cascade,
    ts       timestamptz not null,
    level    text        not null,
    message  text        not null
);

create index run_event_run_idx on run_event (run_id, id);

create table run_artifact (
    id            bigserial primary key,
    run_id        uuid        not null references run (id) on delete cascade,
    name          text        not null,
    content_type  text        not null,
    content       text        not null,
    size_bytes    int         not null,
    created_at    timestamptz not null default now(),
    unique (run_id, name)
);
