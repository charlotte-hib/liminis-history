-- The people's Wikipedia articles, one per edition (person.enwiki, person.frwiki) the import found: the intro as plain
-- text, as Wikipedia gives it, and the page image.
create table person_article (
    person text not null references person,
    language text not null,
    -- The page's title, past any redirect from the sitelink's.
    title text not null,
    url text not null,
    extract text not null,
    -- A thumbnail of the page image, and the image's file name on Commons, to credit it.
    thumbnail_url text,
    image text,
    primary key (person, language)
);
