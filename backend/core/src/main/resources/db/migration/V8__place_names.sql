-- A place's name as Wikidata labels it, in English and in French when it has a French label: the person page lists
-- where they were born, worked, lived and died.
alter table person_place
    add column name varchar(255) not null,
    add column name_fr varchar(255);
