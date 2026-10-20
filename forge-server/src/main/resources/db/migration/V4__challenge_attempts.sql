-- Challenge Arena: an attempt is an experiment created for a challenge. Completion is derived from
-- the experiment's runs by the challenge's criteria; completed_at records when they first all passed.
create table challenge_attempt (
    id             uuid primary key,
    challenge_id   text        not null,
    experiment_id  uuid        not null unique references experiment (id) on delete cascade,
    hints_revealed int         not null default 0,
    answers        jsonb       not null default '{}',
    started_at     timestamptz not null default now(),
    completed_at   timestamptz
);

create index challenge_attempt_challenge on challenge_attempt (challenge_id, started_at desc);
