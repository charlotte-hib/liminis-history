-- The site model, as the entities in art.liminis.domain map it: Hibernate validates them against these tables.
-- Columns are not null wherever the entity's property is not nullable. Text columns keep the entities' lengths.

create table region (
    code varchar(255) not null primary key,
    name varchar(255) not null
);

create table era (
    id varchar(255) not null primary key,
    region_code varchar(255) not null references region,
    label varchar(255) not null,
    governed_by varchar(255) not null,
    start_year integer not null,
    end_year integer not null
);

create table person (
    id varchar(255) not null primary key,
    slug varchar(255) not null unique,
    name varchar(255) not null,
    birth_year integer not null,
    death_year integer,
    dates_approximate boolean not null,
    region_code varchar(255) not null references region,
    domain varchar(255) not null check (domain in ('POWER', 'ARTS', 'EVERYDAY')),
    occupation varchar(255) not null,
    wikipedia_title varchar(255),
    bio_short varchar(2000),
    about varchar(2000),
    wikipedia_url varchar(2000),
    portrait_url varchar(2000)
);

create table life (
    id varchar(255) not null primary key,
    era_id varchar(255) not null references era,
    label varchar(255) not null,
    description varchar(2000) not null,
    start_year integer not null,
    end_year integer not null
);

create table event (
    id varchar(255) not null primary key,
    era_id varchar(255) not null references era,
    title varchar(255) not null,
    event_year integer not null,
    description varchar(2000) not null,
    source_url varchar(2000) not null
);

create table event_participant (
    id bigint not null primary key,
    event_id varchar(255) not null references event,
    person_id varchar(255) not null references person,
    role varchar(255) not null
);

create table connection (
    id bigint not null primary key,
    first_id varchar(255) not null references person,
    second_id varchar(255) not null references person,
    kind varchar(255) not null,
    connection_year integer not null,
    text varchar(2000) not null,
    source_url varchar(2000) not null
);

-- art (url, credit, source_url) and world (governs, everyday, arts, meanwhile) are optional embedded values.
create table moment (
    id varchar(255) not null primary key,
    region_code varchar(255) not null references region,
    place varchar(255) not null,
    period varchar(255) not null,
    start_year integer not null,
    end_year integer not null,
    focus_year integer not null,
    hook varchar(2000) not null,
    url varchar(2000),
    credit varchar(255),
    source_url varchar(2000),
    governs varchar(2000),
    everyday varchar(2000),
    arts varchar(2000),
    meanwhile varchar(2000),
    status varchar(255) not null check (status in ('DRAFT', 'PUBLISHED')),
    featured boolean not null
);

create table story_card (
    id bigint not null primary key,
    moment_id varchar(255) not null references moment,
    position integer not null,
    type varchar(255) not null check (type in ('SCENE', 'PERSON', 'LIFE', 'EVENT')),
    kicker varchar(255),
    title varchar(255),
    text varchar(2000),
    card_year integer,
    person_id varchar(255) references person,
    life_id varchar(255) references life,
    event_id varchar(255) references event,
    url varchar(2000),
    credit varchar(255),
    source_url varchar(2000)
);

create table door (
    id bigint not null primary key,
    origin_id varchar(255) not null references moment,
    target_id varchar(255) not null references moment,
    position integer not null,
    kind varchar(255) not null,
    text varchar(255) not null
);

create table door_face (
    door_id bigint not null references door,
    position integer not null check (position >= 0),
    faces_id varchar(255) not null references person,
    primary key (door_id, position)
);

-- Hibernate's generated ids, 50 at a time. Owned by their columns, so emptying the tables with
-- truncate ... restart identity starts them over.
create sequence event_participant_seq increment by 50 owned by event_participant.id;
create sequence connection_seq increment by 50 owned by connection.id;
create sequence story_card_seq increment by 50 owned by story_card.id;
create sequence door_seq increment by 50 owned by door.id;
