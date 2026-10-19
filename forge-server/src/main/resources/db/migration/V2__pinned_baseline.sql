-- Regression gate: an experiment may pin one succeeded run as its baseline. Every other succeeded
-- run of the experiment is checked against it on demand (runs are immutable once finished, so
-- nothing derived needs to be stored).
alter table experiment
    add column pinned_baseline_run_id uuid references run (id) on delete set null;
