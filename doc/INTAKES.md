# Intake catalog

Read-only world sources in `dvergr/intake/`. Use them with
`(require '[dvergr.intake.<name> :as x])`. Most are a thin shape over
`dvergr.intake.core/fetch-json` — **copy any one to build your own** (see `AGENTS.md`).

## Keyless — work out of the box

| namespace | key fns | source |
|---|---|---|
| `dvergr.intake.hn` | `search-stories` `fetch-top` | Hacker News (Algolia) |
| `dvergr.intake.lobsters` | `fetch-hottest` | Lobsters |
| `dvergr.intake.devto` | `fetch-top` | Dev.to |
| `dvergr.intake.gleif` | `search-entities` `fetch-entity` `map-corporate-tree` | GLEIF (legal entities / ownership) |
| `dvergr.intake.crt-sh` | `search-certificates` `discover-subdomains` | crt.sh (certificate transparency) |
| `dvergr.intake.sec-edgar` | `search-companies` `fetch-company-facts` `fetch-filings` | SEC EDGAR (US filings) |
| `dvergr.intake.wikidata` | `search-entities` `fetch-company-profile` `custom-sparql` | Wikidata (SPARQL) |
| `dvergr.intake.wayback` | `search-snapshots` `check-availability` | Internet Archive |
| `dvergr.intake.arxiv` | `search-papers` `fetch-paper` | arXiv (Atom XML) |
| `dvergr.intake.rss` | `fetch-feed` `discover-feeds` | RSS 2.0 / Atom |
| `dvergr.intake.mastodon` | `fetch-trending` | Mastodon (public) |
| `dvergr.intake.twitter` | `lookup-tweet` | FXTwitter |
| `dvergr.intake.youtube` | `get-transcript` | YouTube transcripts (InnerTube) |
| `dvergr.intake.web-fetch` | `fetch-page` | any URL → readable text |
| `dvergr.intake.linkedin` | `parse-company` `parse-profile` `parse-jobs` | LinkedIn page parser (no network) |

## Keyed — need an env var (via `(env/get …)`)

| namespace | env var(s) | source |
|---|---|---|
| `dvergr.intake.github` | `GITHUB_TOKEN` (optional, raises rate limit) | GitHub REST |
| `dvergr.intake.web-search` | `BRAVE_API_KEY` | Brave Search |
| `dvergr.intake.finnhub` | `FINNHUB_API_KEY` | stock quotes / financials |
| `dvergr.intake.companies-house` | `COMPANIES_HOUSE_API_KEY` | UK Companies House |
| `dvergr.intake.adzuna` | `ADZUNA_APP_ID` `ADZUNA_APP_KEY` | job market |
| `dvergr.intake.bluesky` | `BLUESKY_HANDLE` `BLUESKY_APP_PASSWORD` | Bluesky (AT Protocol) |
| `dvergr.intake.zulip` | `ZULIP_EMAIL` `ZULIP_API_KEY` `ZULIP_SITE` | Zulip |

## The shared substrate — `dvergr.intake.core`

- `(fetch-json url :query-params {…} :headers {…} :timeout ms)` → parsed JSON (keyword keys) or `{:error …}`
- `(fetch-text url …)` → raw body string or `{:error …}`
- `(days-ago-iso n)` / `(days-ago-epoch n)` — date helpers

It's built over `babashka.http-client` + `cheshire.core` — read it; a new intake
needs nothing more.
