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

These run natively (you can't break out through them) and are always available —
They're the REAL libraries you know, mounted under their real names (sandboxed) —
`require`+alias them as in babashka, or call fully-qualified:

| ns | what |
|----|------|
| `babashka.http-client` | `(http/get url opts)` → `{:status :headers :body}`. **Body is a raw string — parse it yourself.** The network seam. |
| `cheshire.core` | JSON: `(json/parse-string s true)` (keywords) / `(json/generate-string m)` |
| `clojure.data.xml` | `(xml/parse-str s)` → hardened `{:tag :attrs :content}` (XXE-safe) |
| `babashka.fs` | `(fs/list-dir d)` `(fs/glob d "**/*.clj")` … — path-clamped. Content I/O is `slurp`/`spit`. |
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

## License

Copyright © 2026 Christian Weilbach. Apache License 2.0 — see [LICENSE](LICENSE).
Matches [dvergr](https://github.com/replikativ/dvergr) and the wider replikativ
stack, so code moves freely between this workspace and dvergr in either direction.
