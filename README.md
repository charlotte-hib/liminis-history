# Liminis

[![CI/CD](https://github.com/charlotte-hib/liminis-history/actions/workflows/ci.yml/badge.svg)](https://github.com/charlotte-hib/liminis-history/actions/workflows/ci.yml)

A history app you step into. Pick a moment (one place over a few years, like Paris in the 1870s or Kyoto in the 1590s), watch its story told card by card over a painting of the period, then meet everyone who lived there: rulers, artists, and the everyday lives around them. Every connection between people is a dated, sourced event.

## Requirements

- Java 25 (`.java-version`, through jenv)
- Node 24 LTS (`.nvmrc`, through nvm)

## Run

Backend (Spring Boot 4, Kotlin, Postgres, virtual threads) on http://localhost:8080. Docker must be running: `bootRun` starts Postgres in a container (Spring Boot's Docker Compose support, `backend/compose.yaml`) and stops it on exit, and the tests start their own (Testcontainers).

```sh
cd backend
./gradlew bootRun
```

Frontend (Next.js 16) on http://localhost:3000:

```sh
cd frontend
nvm use
cp .env.example .env.local
npm install
npm run dev
```

Or both in Docker, on http://localhost:3000:

```sh
docker compose up --build
```

## End-to-end tests

A few Playwright tests (`e2e/`) walk the main journeys in a desktop browser and on a phone with a touchscreen: playing a story by tapping its cards, searching for someone, and opening who was at a documented event; two more check the rate limit per visitor. They run against a site that is already up (`BASE_URL`, http://localhost:3000 by default), and the story tests read the cards from its backend (`API_URL`, http://localhost:8080 by default), which the site does not forward. Start the app first, in Docker or with the two commands above:

```sh
docker compose -f docker-compose.yml -f e2e/compose.yaml up --build --detach --wait   # optional: skips Wikipedia
cd e2e
nvm use
npm ci
npx playwright test
npx playwright show-report   # after a failure: traces and screenshots
```

Outside CI they drive the installed Google Chrome; CI installs Playwright's Chromium. The tests live in their own package, outside `frontend/`, so none of it ends up in an image.

## How it works

- **Moments are the way in.** A `Moment` is a place over a few years with a hook, a public-domain painting, "the world around" it (who governs, everyday life, arts and ideas, meanwhile elsewhere), a story of ordered `StoryCard`s (scene, person, everyday life, documented event) and curated `Door`s to other moments ("Meanwhile, elsewhere", "Follow Zola and Clemenceau"). Moments without a written story get doors to the nearest moments, here and elsewhere.
- **People live in their world.** A person's page shows the world around them and "their life in their time": who governed when they were born, each change of regime and each documented event, with their age. It is computed from dated records (`LifeInTime.kt`), never written by hand.
- **Facts, computed views and prose are separate.** Dates, places and events are curated data (`sample/`, to be replaced by a Wikidata import). Bios and portraits come from the Wikipedia REST API (`WikipediaJob`: one virtual thread per lookup, at most two at a time, retrying when rate limited). Prose is drafted by Claude, then reviewed (below).

### Backend (`backend/`)

Spring Boot 4 and Kotlin, JPA entities, MapStruct for DTOs, and MockMvc API tests that run against `sample/`.

Four Gradle projects: `core` holds the model every app shares (JPA entities, repositories, small helpers), the release compiler and loader, and the Wikipedia enrichment; `site` the public API, the only project in the production image; `workbench` the curator's tools, never in an image (today, story drafting and the Wikidata import, below); and `auth`, the curator's sign-in to the workbench, never in an image either.

The data is a **release**: a directory of JSON Lines files, described in [`sample/README.md`](sample/README.md). At startup the site reads the release in `app.release.dir` (Jackson), checks it and maps it to entities (MapStruct, `ReleaseMapper`), then stores it in Postgres. Its JSON Schema, `sample/release.schema.json`, is generated from the record classes (`./gradlew :core:releaseSchema`); the tests check it is current and that `sample/` matches it. `./gradlew bootRun` and the tests load `sample/`. The Docker image holds no data: Compose mounts the release read-only at `/app/release`, `sample/` locally and in CI. Production serves a private release instead: the curator's data lives in a private repository, whose CI checks it on the site and packs it into a private package on GHCR; `release.lock` pins that package by digest, and the deploy job pulls it and sends it to the server.

Each release gets a database schema of its own, named after the deployed commit (`release_<12 characters of the commit>`; `release_local`, rebuilt on every start, for local runs and tests). The schema is SQL, in `core` (`db/migration`): on start, Flyway creates the release's schema and applies the migrations, and the last one, `LoadRelease`, compiles the release and inserts it in batches (MapStruct maps the entities to rows, Spring's `SimpleJdbcInsert` writes them). A restart, or a rollback to a release still kept, finds its schema loaded and starts at once; the backend keeps the current schema and the two newest others, and drops the rest. Hibernate only checks the entities match the tables (`ddl-auto: validate`). In Compose, two roles split the work (`db/roles.sql`, applied by the one-shot `db-roles` service on every start): `site_owner` runs Flyway and owns the schemas; `site_reader`, the API's, may only read them and fill in Wikipedia's bios and portraits (`ReaderPrivilegesTests`). The tests run on the same Postgres image as the site (the `db` service in `docker-compose.yml`), in a container started by Testcontainers (`PostgresTestConfiguration`, in `core`'s test fixtures); a migration test runs them on an empty database. In Compose, Postgres is on the Compose network only, sorts text by code point (the `builtin` collation, which a system upgrade cannot change), and reads the passwords from `db.env` (the superuser's, for `db` and `db-roles` only) and `db-roles.env` (the two roles'), which `deploy/deploy.sh` creates on the server; without them, locally and in CI, `db/defaults.env` and `db/roles.env` apply.

### Frontend (`frontend/`)

Next.js 16 App Router with server components for data. Calls to the backend go through `openapi-fetch`, typed by `lib/api-schema.ts`, which `openapi-typescript` generates from `api/openapi.yaml` (`npm run api:types`; CI fails when it is out of date). The story player, the moment's year slider (Base UI) and search are client components. shadcn/ui, `motion` for transitions, Lucide icons, and a theme that follows the device: warm paper by day, a dark "gallery" at night, and whatever sits on a painting dark in both.

`proxy.ts` limits how fast one visitor can load pages and call the API: 300 requests at once, then 5 a second, and `429 Too Many Requests` with `Retry-After` beyond that (`lib/rate-limit.ts`). A visitor is an IPv4 address or an IPv6 /64, taken from the `X-Forwarded-For` header that Caddy sets. Static files and Next.js prefetches (which hold no data) do not count. Requests without that header (local runs, CI) and from private addresses are not limited. The frontend's log counts refused requests, never with an address.

## The workbench

The curator's tools run on the curator's machine only, never in an image: the workbench, a server on `127.0.0.1:8090`, `wb`, its command line, and `auth`, which signs them in. Start both servers, with Docker running, and leave them running:

```sh
cd backend
./gradlew :auth:bootRun        # in one terminal
export ANTHROPIC_API_KEY=...   # for story drafts only
./gradlew :workbench:bootRun   # in another
```

The workbench has a Postgres of its own (`compose.workbench.yaml`, at the repository's root, with a named volume), which Spring Boot's Docker Compose support starts and stops with it; `auth` keeps its tables in the same database, in schema `auth`, and starts it too when it starts first, but never stops it. It loads `sample/` into it and fills in the Wikipedia leads of its people (about a minute). Then, from another terminal:

```sh
backend/workbench/wb import            # fetch Wikidata's people (hours), or resume
backend/workbench/wb map               # map the last import to the dataset's rows
backend/workbench/wb export            # export them, push them privately, pin them in the data repository
backend/workbench/wb claims            # propose the facts of the moments' people for review
backend/workbench/wb occupations       # propose a domain to choose for every occupation held, most held first
backend/workbench/wb sample            # write sample/'s slice of the last export (no workbench needed)
backend/workbench/wb draft edo-1830s   # draft a moment's story with Claude
backend/workbench/wb queues            # what waits for review
```

Each command starts a run in the background, one at a time, and follows it; `wb runs` lists them. `wb export` writes the fetched dataset (the mapped rows, facts only, no decisions) to `backend/dataset-export/`: `manifest.json`, then people, their Wikipedia articles, places and occupations as gzipped JSON Lines in Q-id order, about 41 MB for 58,500 people. The same rows make the same bytes. It pushes them with ORAS (run in Docker) to a private package on GHCR, with the token of `gh` (which needs `write:packages`), and writes its digest to `data/fetched.lock` in the private data repository's clone next to this one, for a pull request there. Its API is `api/workbench.yaml`, spec first like the site's. Every request needs an access token from `auth` with the scope of its endpoint, and must be addressed to `localhost` or `127.0.0.1`: a web page open in the curator's browser can send requests to localhost, but has no token to send.

### Signing in: `auth`

`auth` is an OAuth 2.1 and OpenID Connect authorization server on `localhost:9000` (Spring Authorization Server, part of Spring Security), on the curator's computer only. The curator signs in with a passkey: Touch ID, nothing to remember, nothing to leak. It issues access tokens to two clients, registered from its code on every start:

- **curate**, for the curator: authorization code with PKCE, after a passkey, then refresh tokens, each used once. Scopes `curation:read` and `curation:write`. The session lasts 8 hours from the sign-in.
- **wb**, on its own: client credentials, scopes `curation:read` and `runs`. `wb` asks for a token on each command, and again every 5 minutes while it follows a run.

Access tokens are JWTs signed with ES256, for the audience `workbench`, valid 10 minutes. The workbench checks each one against the keys `auth` publishes (`/oauth2/jwks`), its issuer, audience, expiry and the scope its endpoint asks for: `curation:read` to read queues, claims, people and evidence; `curation:write` to add claims, decide and undo; `runs` to start and follow runs. An endpoint with no rule is refused to everyone, and a test fails until it has one. The signing key lives in schema `auth`; every 30 days a new one signs, and the old one stays published 30 days more, so nobody is signed out and nothing restarts. Each client's secret is written once, readable by the curator only, to `~/.config/liminis/curate-client-secret` and `wb-client-secret`; `auth` keeps only a hash, and makes a new secret when its file is gone.

The first start has no passkey: `auth` prints a one-time sign-in link in its log, valid 5 minutes and once. It leads to a page that registers a passkey; curate then asks for that passkey, as the link alone gives no access to the workbench. To register another passkey later (a new computer), start `auth` with `--args=--app.auth.one-time-link=true`.

### Claims

Anything a careful reader could dispute is a claim in the workbench's schema `curation` (never dropped: it holds the curator's work): a person's facts as fetched, a connection, an event, an anecdote. Each has an id derived from what it is about (`person-facts:Q535`), a status (`CANDIDATE`, `APPROVED`, `REJECTED`, `NEEDS_WORK`), its sources (a Wikidata entity, a Wikipedia article, a book's page, a web page, a podcast's minute) and flags saying why it needs a person's eye. Every decision is logged with the payload before and after, and who made it; the curator's last one can be undone. `wb claims` proposes the facts of the people born in each moment's region and alive during it, the 50 best known per moment. Proposing again after an import refreshes them: a claim not yet decided takes the new facts, an approved one goes back to review, flagged. Claims can also be added by hand (`POST /api/claims`), from a podcast or a book, with at least one source. `wb occupations` proposes one claim per occupation the dataset's people hold among their first three, and per class above them up to three levels, those most held as a first occupation first: the curator chooses its domain (power, arts, everyday) or none, and the site's people take the domain of their first occupation mapped.

### Reviewing claims: `curate`

`curate/` is the curator's review app, a Next.js app on the curator's computer only, never in an image. With the workbench running:

```bash
cd curate && npm ci && npm run dev   # http://localhost:3100
```

The inbox counts what waits in each queue and why. A queue lists its claims, conflicts first, then the other flagged ones, beside the claim under review: a person's facts field by field against every statement Wikidata holds (with how many references name a source) and the person's Wikipedia intros (`GET /api/people/{qid}/evidence`). Keys: `A` approve, `E` edit (then `⌘⏎` to approve as edited), `R` reject, `N` keep for later, each with an optional note, `S` or `J` the next claim, `K` the previous one, `U` undo the last decision. A decision carries the version the claim was read at (`If-Match`): when it changed meanwhile, in another tab, the workbench refuses and curate offers to read it again. "Add a claim" adds an anecdote about one of the fetched people (found by name, `GET /api/people`), with where it was heard or read.

Only curate's server talks to `auth` and the workbench, as a backend-for-frontend: it signs the curator in through `auth` (`openid-client`), keeps the tokens in an encrypted, `HttpOnly` cookie (`jose`, with a key it makes in `~/.config/liminis/curate-cookie-key`), refreshes them in `proxy.ts` before they expire, and sends the access token with each call to the workbench (`WORKBENCH_URL`, `AUTH_URL` and `LIMINIS_SECRETS_DIR` to point it elsewhere). The browser never holds a token. "Sign out" revokes the refresh token and ends the session at `auth` too. Like the workbench, it answers only requests addressed to `localhost` or `127.0.0.1`, and sends `127.0.0.1` on to `localhost`, the name `auth` sends the browser back to. Its end-to-end tests (`e2e/curate/`) sign in with a passkey (Chrome's virtual authenticator) and decide on claims, so they run against `auth` and a workbench on a throwaway copy of its database, never in CI: `AUTH_LOG=<auth's output> npx playwright test --config curate.config.ts`.

## Drafting a story with Claude

Stories are prose, so they are drafted by Claude and reviewed by a person before they ship: `wb draft <moment>`.

The workbench reaches Wikimedia through one client, `WikimediaClient`: Wikidata's `wbgetentities` (up to 50 entities a request) and Wikipedia's `action=query` (leads, thumbnails and revisions of up to 20 pages), always with `maxlag=5`, and Wikidata's query service (SPARQL), all with a User-Agent with a contact. Every call shares one pace, Resilience4j instances named `wikimedia` in its `application.yaml`: one request at a time (a bulkhead), two a second at most (a rate limiter), and, when Wikimedia answers 429 or the `maxlag` error, a wait as long as its `Retry-After` asks before trying again (a retry), during which every other call waits too. Its tests run against WireMock, with responses recorded from the real APIs.

`StoryDrafting` gathers the moment's sources (Wikipedia leads of the people alive there, its documented events, eras and typical lives, and people alive elsewhere), asks `claude-opus-5-5` for a story as structured output where every line carries a quote from a source, then checks every quote and reference (`StoryDraftValidator`). Nothing is published: the draft and the validator's findings go to `backend/drafts/<moment>.json` for a curator to correct and copy into `sample/moments/<moment>.json`. The request opts into server-side refusal fallbacks (`fallbacks: "default"`).

## Importing people from Wikidata

The workbench fetches people from Wikidata into a schema of its own in its Postgres, `raw`, which keeps what came back as it came (`jsonb`) and is never dropped, unlike the release schema rebuilt from `sample/` on every start. Its migrations are in `workbench` (`db/raw`), run by a Flyway of their own.

`wb import` runs it.

1. **Discovery** asks Wikidata's query service for humans born from 3500 BCE to today with at least 25 sitelinks, or 10 when born before 1800, leaving out people with no date of death born less than 110 years ago. One query per slice of birth dates, under the service's 60 seconds: a century before 1500, a decade before 1900, then a year. A slice the service stops anyway is asked for again in halves. Q-ids and sitelinks go to `raw.discovered`.
2. **People**: their entities, 50 a request (`wbgetentities`: labels and aliases in English, French, Japanese and `mul`, statements, English and French Wikipedia sitelinks), into `raw.entity` with their revision.
3. **Linked**: the places of birth, death, work and residence, and the occupations, those people's statements point to, the same way.

Run it again later and it refreshes: for entities already stored it first asks for their latest revisions only (`props=info`, 50 a request), and fetches again only those that changed. Every request goes through `WikimediaClient`'s pace. Progress is kept in `raw.import_run`, `raw.discovery_slice` and `raw.import_item`, one transaction per slice or batch, so a run that stops resumes at the next one, after waiting out any `Retry-After` Wikimedia gave before it stopped. Each run counts its requests, the waits Wikimedia asked for, and the entities fetched, unchanged and gone. The leads filled in when the workbench starts call Wikipedia outside that pace: start an import once they are done.

## API

The backend's API, described in [`api/openapi.yaml`](api/openapi.yaml) (OpenAPI 3.0). Through the site, only the paths the browser calls are forwarded (`/api/search`, `/api/eras/{id}`, `/api/years/{year}/people` and `POST /api/events`); the frontend's server fetches the rest while rendering.

- `GET /api/moments` lists published moments, with who governed, the story length and its cast.
- `GET /api/moments/{id}` returns a moment: the world around it, its eras, people, everyday lives, events and doors.
- `GET /api/moments/{id}/story` returns its story cards and doors (404 when no story is written yet).
- `GET /api/people/{slug}?year=1875` returns a person in the world around them in that year (their prime if no year is given), with their life in their time.
- `GET /api/years/{year}/people?exclude=FR` returns a few people alive that year in each other region.
- `GET /api/search?q=zola` searches people and moments, ignoring accents.
- `GET /api/regions`, `GET /api/eras`, `GET /api/eras/{id}`.
- `POST /api/events` counts one anonymous visitor event (see [Metrics](#metrics)): 204 when counted, 400 when outside the allowed names and values, 413 above 1 KB.

**Spec first.** The spec is the contract: the backend's controller interfaces and response models are generated from it at build time (openapi-generator, `kotlin-spring`, interfaces and models only), and the controllers implement them. The frontend's types and client come from it too (`openapi-typescript`, `openapi-fetch`). Change the spec, not the generated code (`backend/site/build/generated/openapi`). The spec's constraints (`perRegion` from 1 to 5, `exclude` as a two-letter code, a query of at most 100 characters) become Bean Validation annotations, which Spring enforces: a request outside them gets a 400. Errors are RFC 9457 Problem Details (`application/problem+json`). CI lints it with Redocly (`api/redocly.yaml`). On `./gradlew bootRun`, Swagger UI shows it at http://localhost:8080/swagger-ui.html; it is not in the production image.

### API tests

`http/api.http` is a short end-to-end smoke suite: a few requests against a running backend, each with assertions on the status, the content type and the data from `sample/` it returns. Start the backend without Wikipedia enrichment (`./gradlew bootRun --args='--app.wikipedia.enrich=false'`), then either open the file in IntelliJ and run the requests with the `local` environment, or run them all with the [HTTP Client CLI](https://www.jetbrains.com/help/idea/http-client-cli.html):

```sh
ijhttp --env-file http/http-client.env.json --env local http/api.http
```

CI runs the same file against the backend image, with its database (the "API tests" job), never against production.

`http/production.http` is the only file that runs against the live site: read-only GETs through its public address (the HTTP to HTTPS redirect, the home page, a story and a person found from it, the public API paths, a 404), checking shapes rather than data, so curated releases never break it. `.github/workflows/smoke.yml` runs it after each deploy and every day, with a check that the TLS certificate is valid for 10 more days. The address comes from the repository variable `SITE_URL`. It never sends visitor events.

## Metrics

Two Grafana dashboards, provisioned from `monitoring/`: **Visitors** (page views, stories started and finished, the card where readers stop, people opened in a panel or on their full page and from where, searches) and **Service** (requests, p95 latency, 5xx, JVM memory, CPU, GC).

- **What is counted.** The frontend (`lib/analytics.ts`) sends a few events with `navigator.sendBeacon` to `POST /api/events`: `page_view` (by page template: home, moment, story, person), `story_started`, `story_card_reached` (card number), `story_completed` (the doors after the last card, once that card was read), `person_panel_opened` and `person_full_page_opened` (where from: the page underneath, search, the panel, a link from outside), `search_used` and `search_result_opened`. The backend (`VisitorEvents.kt`) only adds one to a Prometheus counter; every label comes from a closed set (page templates, published moments, card positions within their story), and anything else is rejected and counted as rejected.
- **What is not.** No cookies, no ids, no IP addresses, user agents, URLs or referrers are sent or stored, and nothing links two events to the same visitor: the counters only say how often something happened. The only thing kept in the browser is a one-shot flag in `sessionStorage` that tells a person's full page it was opened from the panel; the page removes it on arrival. Automated browsers (`navigator.webdriver`) send nothing.
- **Where it runs.** Spring Boot Actuator serves `/actuator/prometheus` (and health) on its own port, 8081, in the Docker image; nothing publishes that port, and the frontend only forwards the browser's few API paths to 8080. Prometheus (30 days, at most 1 GB) and Grafana run in Compose under the `monitoring` profile, with small memory limits. Grafana listens on `127.0.0.1:3001` only, never through Caddy, with sign-up and anonymous access off.
- **One alert.** Provisioned from `monitoring/grafana/provisioning/alerting/traffic.yaml`: an email when the backend serves more than one API request a second (visitor events aside) for 10 minutes, which is hundreds of pages in a few minutes. The usual peak is under 0.1. It goes to `ALERT_EMAIL` through the SMTP server set in `grafana.env`: `GF_SMTP_ENABLED=true`, `GF_SMTP_HOST` (host:port), `GF_SMTP_USER`, `GF_SMTP_PASSWORD` and `GF_SMTP_FROM_ADDRESS`. Without them, Grafana still starts and the alert only shows in its alert list.

Locally, with a password of your choice in `grafana.env` (untracked):

```sh
echo "GF_SECURITY_ADMIN_PASSWORD=$(openssl rand -base64 24)" > grafana.env
docker compose --profile monitoring up --build
```

Then open http://localhost:3001 and sign in as `admin`. On the server, `deploy/deploy.sh` starts Prometheus and Grafana once `grafana.env` exists next to `.env`; reach Grafana through an SSH tunnel (`ssh -L 3001:localhost:3001 <server>`, then http://localhost:3001). To serve it under another address, for example through `tailscale serve`, add `GF_SERVER_ROOT_URL=<that address>` to the server's `grafana.env`, which overrides `monitoring/grafana/defaults.env`. The dashboards are read from the repository: edit them in Grafana, export the JSON into `monitoring/grafana/dashboards/` and commit it.

## Deployment

The pipeline (`.github/workflows/ci.yml`) holds no long-lived secrets:

1. **Every pull request** runs the backend tests and the frontend lint and build, then builds each image once (saved for a day as a workflow artifact) and runs the API tests and the end-to-end tests against those exact images. `main` is protected by a ruleset: changes land only through pull requests that pass the backend tests and the frontend lint and build, with linear history and no force push or deletion.
2. **On `main`**, the same images are pushed to GHCR tagged with the commit, and given a signed SLSA build provenance attestation and a signed SPDX SBOM (GitHub artifact attestations, Sigstore).
3. **The deploy job** (GitHub environment `production`) waits for the API and end-to-end tests, verifies each image's provenance with `gh attestation verify`, pins it by digest, pulls the private release `release.lock` pins (ORAS, with the job token: the package grants this repository read access), joins the tailnet through Tailscale workload identity federation (GitHub OIDC, no auth key), and runs `deploy/deploy.sh` over Tailscale SSH (no SSH key).
4. **`deploy/deploy.sh`** checks out the commit's Compose config, creates the database's passwords when missing (`db.env` and `db-roles.env`, kept on the server), unpacks the release it received into `releases/<id>` (it keeps the three most recently deployed), starts the pinned images on it, and probes the site. If it is not healthy within three minutes, it rolls back to the previous release and fails the job. Once the release is healthy, it starts or updates Prometheus and Grafana (when the server has `grafana.env`); a monitoring failure is reported but never rolls the site back.

Dependabot keeps Actions (pinned by commit), Gradle, npm and base images up to date.
