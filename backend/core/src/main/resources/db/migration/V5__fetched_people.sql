-- People from the fetched dataset beside those written by hand: Wikidata's id, up to three occupations, how widely
-- they are written about (sitelinks, and fame within their region and century), and every place tied to them.
alter table person
    drop column occupation,
    add column qid varchar(255) unique,
    add column occupations varchar(255)[] not null,
    add column sitelinks integer not null,
    add column fame integer not null check (fame between 0 and 100);

create table person_place (
    person_id varchar(255) not null references person,
    position integer not null check (position >= 0),
    place_qid varchar(255) not null,
    kind varchar(255) not null check (kind in ('BIRTH', 'DEATH', 'WORK', 'RESIDENCE')),
    region_code varchar(255),
    start_year integer,
    end_year integer,
    primary key (person_id, position)
);

alter table moment add column place_qid varchar(255);

-- Who was alive in a region in a year, by their own region or by any of their places, ranked by sitelinks.
create index person_region_lifespan_idx on person (region_code, birth_year, death_year);
create index person_place_region_idx on person_place (region_code, person_id);
create index person_place_place_idx on person_place (place_qid, person_id);
