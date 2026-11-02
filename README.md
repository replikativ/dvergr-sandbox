# Agent Workspace

This is your project — a real git repository you own. Write code here with the
file tools, `(require '[my.ns])` it like a normal Clojure REPL, run it, and
`git commit` your work. It persists with the room and forks when the room forks.

## What's here

- **`dvergr/intake/`** — a library of read-only data sources (Hacker News, arXiv, SEC,
  GitHub, Wikidata, RSS, …), written as plain Clojure you can **read, copy, and
  extend**. Each is a thin composition over the sandbox capability primitives.
  Browse `dvergr/intake/hn.clj` (the simplest), `dvergr/intake/arxiv.clj` (parses XML),
  `dvergr/intake/core.clj` (the shared `fetch-json`/`fetch-text` helpers).
- **`sources/`** — a place for your own code.
- **`user.clj`** — auto-loaded entry point.

## Sandbox capability primitives

The host mounts familiar libraries under their real names, with sandbox and
permission boundaries. Availability varies by host, profile and Run; discover
the current namespace with `sandbox/doc`. Require and alias installed libraries
as in babashka, or call fully qualified:

| ns | what |
|----|------|
| `babashka.http-client` | `(http/get url opts)` → `{:status :headers :body}`. Body defaults to text; hosts on Dvergr 0.1.385+ support `:as :bytes`/`:byte-array` for binary downloads, bounded by `:max-bytes` (128 MiB ceiling). Network grants still apply. |
| `cheshire.core` | JSON: `(json/parse-string s true)` (keywords) / `(json/generate-string m)` |
| `clojure.data.xml` | `(xml/parse-str s)` → hardened `{:tag :attrs :content}` (XXE-safe) |
| `babashka.fs` | `(fs/list-dir d)` `(fs/glob d "**/*.clj")` … — path-clamped. Text I/O is `slurp`/`spit`; recent hosts also expose `read-all-bytes`, `write-bytes` and bounded `unzip`. |
| `babashka.process` | `(p/shell "git log -5")` → `{:exit :out :err}` (muschel-backed, jailed) |
| `dvergr.codec` | `base64-encode`/`url-encode`/`strip-tags` (no babashka equivalent) |
| `env` | `(env/get "API_KEY")` → config/env value |
| `git` | `(git/add …)` / `(git/commit …)` — structured git in this repo |
| `datahike.api` | the real datahike API ; `dvergr.room` — your room's store + knowledge base |

## Writing your own intake

A data source is just a function that fetches and shapes:

```clojure
(require '[dvergr.intake.core :as intake])

(defn my-source [query]
  (->> (intake/fetch-json "https://api.example.com/search"
                          :query-params {:q query}
                          :headers {"Authorization" (str "Bearer " (env/get "MY_KEY"))})
       :results
       (mapv (fn [r] {:title (:name r) :url (:link r)}))))
```

Save it as `dvergr/intake/my_source.clj` (namespace `dvergr.intake.my-source`), `(require
'[dvergr.intake.my-source] :reload)`, call it, `git commit`. To pull in more libraries,
`git clone` them with the `git`/`bash` tools — this is a normal project.

## Reusable workflows and evaluation

See [the workflow guide](doc/WORKFLOWS.md) for the path from ordinary code to
versioned task files, fixtures, calibrated checkers, admitted experiments and
standard Markdown reports. The guide distinguishes general Dvergr SCI
capabilities from optional Simmis authoring helpers. Authoring a checker does not
make it trusted, and a completed Run does not establish task correctness.

## License

Copyright © 2026 Christian Weilbach. Apache License 2.0 — see [LICENSE](LICENSE).
Matches [dvergr](https://github.com/replikativ/dvergr) and the wider replikativ
stack, so code moves freely between this workspace and dvergr in either direction.
