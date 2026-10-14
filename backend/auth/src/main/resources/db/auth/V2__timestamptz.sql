-- V1's comment says every timestamp became timestamptz; these three had not. Run through the app's own connection,
-- whose time zone is the one the values were written in, the cast keeps each instant.
alter table user_credentials
    alter column created type timestamptz,
    alter column last_used type timestamptz;
alter table one_time_tokens
    alter column expires_at type timestamptz;
