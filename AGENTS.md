# AGENTS.md — navigating this workspace

You are working in a Clojure / babashka project that is **your** workspace. It's a
real git repo: write code, `(require …)` it, run it, `git commit`. This file is your
map; `README.md` is the capability reference.

## Layout

- **`dvergr/intake/`** — a library of read-only data sources (Hacker News, arXiv,
  SEC, GitHub, Wikidata, RSS, …). Each is plain Clojure over the sandbox primitives.
  **Read them to learn the pattern; copy one to make your own.** Catalog:
  [`doc/INTAKES.md`](doc/INTAKES.md).
- **`dvergr/mail/`** — read your room's attached mailbox (if one is attached).
- **`sources/`** — a place for your own code.
- **`user.clj`** — auto-loaded entry point.
- **`README.md`** — the capability primitives + how to extend.

## The primitives are the REAL libraries

The sandbox preloads the libraries you already know, under their real names
(sandboxed underneath): `babashka.fs`, `slurp`/`spit`, `babashka.http-client`,
`cheshire.core`, `clojure.data.xml`, `babashka.process`, `datahike.api`, plus
dvergr's own (`dvergr.room`, `dvergr.mail`, `dvergr.codec`). Require + alias them as
you would in babashka — `(require '[babashka.fs :as fs])`. Full table in `README.md`.

## Writing a data source

A source is just a fn that fetches + shapes. Start from `dvergr/intake/hn.clj`
(simplest) or `dvergr/intake/arxiv.clj` (parses XML). `(require '[dvergr.intake.core
:as intake])` gives you `fetch-json` / `fetch-text`.

For cited research, retain full search/fetch responses and their acquisition
receipts. `dvergr.intake.evidence/quote-span` selects exact text without IO;
see [`doc/EVIDENCE.md`](doc/EVIDENCE.md) for a search → fetch → evidence example.
It constructs evidence claims, not trusted verification.

## Getting more code

- **Source you'll read/edit** — `git clone` a repo into the workspace and require it
  (its `src/` goes on the load path).
- **A library you just want to use** — request it through the gated deps mechanism
  (`babashka.deps/add-deps` with a maven `[org/lib "1.2.3"]` or a `:git/url` coord).
  New platform affordances are **approved by the room's manager**, so a request may
  pause for review.

## Conventions

This repo is the open dvergr sandbox stdlib (`replikativ/dvergr-sandbox`). Edits you
make in your room are **yours** (fork-isolated, shown in the merge diff). Genuinely
useful intakes/helpers are worth sending upstream.
