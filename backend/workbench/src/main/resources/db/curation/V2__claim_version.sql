-- A claim's version, raised by every change to it (a decision, an undo, a refresh): the API sends it as the ETag, and
-- a decision must name the version it was taken on (If-Match), so two people, or two tabs, never overwrite each
-- other's decision without seeing it.
alter table claim add column version bigint not null default 0;
