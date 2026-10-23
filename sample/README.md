# Sample release

A small public release: about a hundred people written by hand, their eras, events and connections, ten moments, and a slice of the fetched dataset from Wikidata and Wikipedia. The backend's tests, the API tests (`http/`), the end-to-end tests (`e2e/`), `./gradlew bootRun` and `docker compose up`, which mounts it into the backend's container, all use it. Production serves a private release in the same format, pinned in `release.lock`.

At startup the backend reads the directory in `app.release.dir`, checks it (`ReleaseReader`, `FetchedDatasetReader`), compiles its people from the records and the dataset (`PeopleCompiler`), resolves its references and sets every life and event in its era (`ReleaseCompiler`, `ReleaseMapper`), then stores it.

[`release.schema.json`](release.schema.json) is the format as a JSON Schema (draft 2020-12): one definition per kind of record in `$defs`, to validate a line or a moment file against. It is generated from the record classes in `backend/core`: run `./gradlew :core:releaseSchema` in `backend/` after changing them. The backend's tests fail when it is out of date, or when a record in `sample/` does not match it.

## Files

| File | One record per | Points at |
|---|---|---|
| `regions.jsonl` | region, by today's borders (`FR`) | |
| `eras.jsonl` | who governed a region, from `start` to `end` (no `end`: until today) | region |
| `people.jsonl` | person written by hand, with their Wikidata id (`qid`) when they have one | region |
| `occupations.jsonl` (optional) | occupation, by its Wikidata id: the `domain` of the people who hold it, or none (no `domain`) when it says nothing of a life's work | |
| `corrections.jsonl` (optional) | person of the dataset, by their Wikidata id: the fields the curator corrected (`name`, `nameFr`, `born`, `died`, `approximate`, `occupations`, Wikidata ids in order, `places`, each a `kind`, a `place` by Wikidata id and its `start` and `end` years when known, the first birthplace giving the region, and `wikipedia` and `wikipediaFr`, the articles' titles), the others left out; `cleared` names those emptied (`NAME_FR`, `WIKIPEDIA`, `WIKIPEDIA_FR`). Written by the workbench's publish, never by hand | |
| `lives.jsonl` | typical life, set in the era that covers its `start`, drawn with a public-domain `art` (`url`, `credit`, `source`, and optionally `position`, two percentages as in CSS `object-position`, and `fit`, `COVER` or `CONTAIN`) or else an `icon` | region |
| `events.jsonl` | dated, sourced event, set in the era that covers its `year` | region, people |
| `connections.jsonl` | two people, how they knew each other, when, and the source | people |
| `anecdotes.jsonl` (optional) | short story about a person, by their Wikidata id, in a `year`, with its `source`: a book (`title`, `page`), a podcast, a video or a documentary (`title`, `episode`, `minute`), or a web page (`url`), at least a title or a URL. Shown among the years of their life. Written by the workbench's publish | people |
| `moments/<id>.json` | moment: the world around it, its story cards and its doors, and the Wikidata id of its place (`placeQid`) | region, people, lives, events, moments |
| `dataset/` (optional) | the fetched dataset, as the workbench's `wb export` writes it: `manifest.json`, then `people`, `articles`, `places` and `occupations` as gzipped JSON Lines. Here only a slice, written by `wb sample`: the people above, and the 30 most written about of each moment's place | |

## Rules

- **One record per line, sorted by `id`**, so a change to one record is a change to one line. Connections have no id: they are sorted by their two people, then by `kind`. Moments are nested, so each one is a file of its own, named after its id.
- **References are ids**: `"region": "FR"`, `"person": "emile-zola"`. Every one must resolve.
- **Years are integers**, negative for BCE. There is no year 0.
- **Optional fields are left out** rather than set to `null` or `false`.
- **Strict**: an unknown field, a missing one, a duplicate id or a line out of order fails the start, with the file and line.
- **French beside the English**: a record may have `fr`, its texts in French, each one its English has and no other (a region's `name`; an era's `label` and `governedBy`; a person's `name`, `occupation`, `bio`, and the title of their French Wikipedia article, `wikipedia`; a life's `label` and `description`; an event's `title` and `description`, with each participant's `role` in a `fr` of their own; a connection's `kind` and `text`; an anecdote's `text`; a moment's `place`, `period`, `hook` and `world`, with each card's `kicker`, `title` and `text` and each door's `kind` and `text` in theirs). The French site shows a record in French when it has one, else in English. The workbench's publish writes them from the translations the curator approved. Painting credits stay as they are.

Consecutive eras share their boundary year; the one that starts later wins it. A person's `slug`, used in addresses, is their name without accents or punctuation; namesakes from the dataset add their year of birth, the one most written about keeping the name.

## People from the dataset

- **Written by hand**, a person keeps every field of their record. With a `qid` found in the dataset, they also take its places, sitelinks and English Wikipedia article (bio, portrait, link); without one, the backend fetches their article from Wikipedia after loading.
- **Every other person of the dataset** comes in when their birthplace lies in a country today (by its coordinates, else by Wikidata's country), which becomes their region. A region that `regions.jsonl` does not list holds people but has no eras and no page.
- **Corrections** apply to the people of the dataset only: someone written by hand keeps their record, and a correction for a Wikidata id the dataset does not hold changes nothing.
- **Occupations**: up to three, in Wikidata's order. The domain is that of the first occupation `occupations.jsonl` maps, directly or through its classes up to three levels up; none at all means `EVERYDAY`.
- **Fame**: a person's percentile by sitelinks among the people of their region and century of birth, from 0 to 100.
