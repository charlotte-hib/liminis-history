-- A connection's source, as an anecdote's: a book (title, page), a podcast, a video or a documentary (title, episode,
-- minute), or a web page (url), with a title or a URL.
alter table connection
    add column source_title varchar(2000),
    add column source_page varchar(255),
    add column source_episode varchar(255),
    add column source_minute varchar(255),
    alter column source_url drop not null,
    add check (source_title is not null or source_url is not null);
