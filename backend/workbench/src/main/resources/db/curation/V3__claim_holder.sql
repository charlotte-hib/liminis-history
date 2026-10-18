-- Who works on a claim: its holder, by username (auth's accounts), since when. Only they, or the admin, edit or decide
-- on it; nobody's claim goes to whoever writes it first. A hold is released by its holder or the admin, never by time.
alter table claim
    add column held_by text,
    add column held_since timestamptz;

create index claim_holder on claim (held_by) where held_by is not null;
