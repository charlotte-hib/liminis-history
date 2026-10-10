-- The people's Wikipedia articles, in each edition of app.wikidata.sites, as action=query returned them: the intro as
-- plain text, the page image (thumbnail and file name), the URL, at the latest revision fetched. Keyed by the title the
-- person's sitelink gives, which may redirect to the page stored.
create table page (
    language text not null,
    title text not null,
    revision bigint not null,
    fetched_at timestamptz not null,
    json jsonb not null,
    primary key (language, title)
);

-- The articles a run fetches, or checks for a new revision, once the entities are done: phase PAGES.
create table page_item (
    run_id bigint not null references import_run,
    language text not null,
    title text not null,
    done_at timestamptz,
    primary key (run_id, language, title)
);

create index page_item_to_do on page_item (run_id, language, title) where done_at is null;

-- Pages stored (new, or at a new revision), not fetched again (same revision), and gone from Wikipedia.
alter table import_run
    add column pages_fetched integer not null default 0,
    add column pages_unchanged integer not null default 0,
    add column pages_missing integer not null default 0;
