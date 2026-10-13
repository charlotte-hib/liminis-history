-- ReaderUpdatesTests: one person to update, written by Flyway as the owner, after every core migration.
insert into region values ('FR', 'France');
insert into person (id, slug, name, birth_year, death_year, dates_approximate, region_code, domain, occupations, sitelinks, fame)
values ('zola', 'emile-zola', 'Émile Zola', 1840, 1902, false, 'FR', 'ARTS', '{writer}', 120, 99);
