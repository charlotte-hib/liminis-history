-- The import the dataset was mapped from, as the exported dataset's manifest states it. One row, written by every
-- mapping run.
create table mapping (
    import_run bigint not null,
    import_started_at timestamptz not null,
    import_finished_at timestamptz not null
);
