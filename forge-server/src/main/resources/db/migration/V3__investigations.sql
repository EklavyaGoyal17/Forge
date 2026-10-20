-- AI Investigator: questions asked about an experiment and the answers given, with the evidence
-- they were grounded in and the result of checking the answer against that evidence. Kept so the
-- notebook can show what was concluded, by which provider, from which evidence.
create table investigation (
    id             uuid primary key,
    experiment_id  uuid        not null references experiment (id) on delete cascade,
    question       text        not null,
    provider       text        not null,             -- 'rules' when no AI provider was used
    model          text,
    answer         text        not null,
    citations      jsonb       not null default '[]',
    check_result   jsonb       not null default '{}', -- unknown citations, numbers not found in the evidence
    evidence_ids   jsonb       not null default '[]',
    created_at     timestamptz not null default now()
);

create index investigation_experiment on investigation (experiment_id, created_at desc);
