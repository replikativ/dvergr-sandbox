# Keep evidence as data

Save complete responses before projecting fields. Host acquisition receipts are
ordinary `:dvergr/acquisition` map entries, not Clojure metadata. Missing receipts
mean the environment did not supply one; never invent identifiers.

```clojure
(require '[dvergr.intake.web-search :as search]
         '[dvergr.intake.web-fetch :as fetch]
         '[dvergr.intake.evidence :as evidence]
         '[clojure.string :as str])

(def found (search/search "shared organizational memory" :count 5))
{:receipt (get-in found [:dvergr/acquisition :id])
 :error (:error found)
 :results (mapv #(select-keys % [:title :url :description]) (:results found))}

;; After inspecting results, choose a URL, then save its whole response.
;; Do not re-fetch just because you omitted a field from an earlier projection.
(def page (fetch/fetch-page (:url (first (:results found)))))
{:receipt (get-in page [:dvergr/acquisition :id])
 :error (:error page)
 :text-preview (when-let [text (:text page)]
                 (subs text 0 (min 800 (count text))))}

;; Choose an exact phrase from the observed text, not a paraphrase.
;; This example phrase must actually occur in the chosen page.
(def phrase "shared organizational memory")
(def selected
  (when (string? (:text page))
    (when-some [start (str/index-of (:text page) phrase)]
      (evidence/quote-span page :text start (+ start (count phrase))))))
selected
```

`quote-span` is pure: it selects a nonempty `[start,end)` substring, preserving
the original acquisition envelope and optional fixture ID/URL. It throws on
missing receipts, invalid bounds or an error response. It neither clips bounds
nor repairs quotations. Offsets are UTF-16 code units, as with Clojure `subs`.
For raw HTTP responses, select `:body` explicitly and retain the request URL
alongside the response (raw responses need not contain it).

The selection carries `:selection {:field :text :start ... :end ... :unit :utf-16}`.
That field matters: `fetch-page` can strip HTML, decode entities or truncate text.
A quote from `:text` is not necessarily a substring of the captured HTTP body.
Verification must account for extraction provenance. Keep the selection descriptor
when storing evidence; adapting it to a task's answer schema is a separate step.

A receipt is correlation, not proof. Capture may be disabled; these values can
also be modified by an agent. Trusted verification must check receipt ownership,
the actual stored response and any transformations, plus whether the evidence
supports the claim. No reference answers or trusted scoring authority are exposed
by this helper.

In `clojure_eval`, only the last form's value is returned. Return a small map or
vector to inspect several things at once. `println`/`prn` output is captured too.
Search results use `:description`, not `:snippet`. These examples perform IO only
at the explicit search/fetch calls; evidence selection itself has no effects.
