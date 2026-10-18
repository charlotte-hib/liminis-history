-- Who may sign in, until now the curator alone, in memory: Spring Security's users and authorities schema
-- (JdbcUserDetailsManager), as its jar ships it, with a name to show, who invited each, and when an account was
-- revoked. An account is never deleted: the decisions it took keep its username.
create table users (
    username varchar(50) not null primary key,
    -- Never used: curators sign in with a passkey, or once with their invitation's one-time link.
    password varchar(500) not null,
    -- False once revoked.
    enabled boolean not null,
    display_name varchar(200) not null,
    invited_by varchar(50) references users,
    created timestamptz not null default now(),
    revoked timestamptz
);

-- One role per account: ROLE_ADMIN, or ROLE_CURATOR.
create table authorities (
    username varchar(50) not null references users,
    authority varchar(50) not null
);

create unique index ix_auth_username on authorities (username, authority);

-- The curator who had the in-memory account becomes the admin, with the passkeys registered under the same name.
insert into users (username, password, enabled, display_name) values ('curator', '', true, 'Curator');
insert into authorities (username, authority) values ('curator', 'ROLE_ADMIN');
