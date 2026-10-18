-- Short stories about a person, in a year, with where they were read or heard: a book (title, page), a podcast, a
-- video or a documentary (title, episode, minute), or a web page (url).
create table anecdote (
    id varchar(255) not null primary key,
    person_id varchar(255) not null references person,
    anecdote_year integer not null,
    text varchar(2000) not null,
    title varchar(2000),
    page varchar(255),
    episode varchar(255),
    minute varchar(255),
    url varchar(2000),
    check (title is not null or url is not null)
);

create index anecdote_person_idx on anecdote (person_id);
