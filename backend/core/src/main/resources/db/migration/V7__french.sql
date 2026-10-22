-- The release's text in French, beside its English: null where a record has no French. A record has all its texts in
-- French or none; a story card, the ones its English has.
alter table region add column name_fr varchar(255);

alter table era
    add column label_fr varchar(255),
    add column governed_by_fr varchar(255);

-- A person's French name and occupations, and their French Wikipedia article's bio and link, filled like the English
-- ones: written by hand, from the fetched dataset, or by WikipediaJob.
alter table person
    add column name_fr varchar(255),
    add column occupations_fr varchar(255)[],
    add column wikipedia_title_fr varchar(255),
    add column bio_short_fr varchar(2000),
    add column about_fr varchar(2000),
    add column wikipedia_url_fr varchar(2000);

alter table life
    add column label_fr varchar(255),
    add column description_fr varchar(2000);

alter table event
    add column title_fr varchar(255),
    add column description_fr varchar(2000);

alter table event_participant add column role_fr varchar(255);

alter table connection
    add column kind_fr varchar(255),
    add column text_fr varchar(2000);

alter table anecdote add column text_fr varchar(2000);

alter table moment
    add column place_fr varchar(255),
    add column period_fr varchar(255),
    add column hook_fr varchar(2000),
    add column governs_fr varchar(2000),
    add column everyday_fr varchar(2000),
    add column arts_fr varchar(2000),
    add column meanwhile_fr varchar(2000);

alter table story_card
    add column kicker_fr varchar(255),
    add column title_fr varchar(255),
    add column text_fr varchar(2000);

alter table door
    add column kind_fr varchar(255),
    add column text_fr varchar(255);

-- WikipediaJob fills in the French article's bio and link as it does the English ones (V2__reader.sql).
grant update (bio_short_fr, about_fr, wikipedia_url_fr) on ${flyway:defaultSchema}.person to ${reader};

-- Names are searched in both languages: "Jeanne d'Arc" finds Joan of Arc on either site.
drop index person_search_name_idx;
alter table person drop column search_name;
alter table person add column search_name text
    generated always as (fold_for_search(name || coalesce(' ' || name_fr, ''))) stored;
create index person_search_name_idx on person using gin (search_name extensions.gin_trgm_ops);
