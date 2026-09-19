(ns dvergr.intake.wayback
  "Internet Archive Wayback Machine intake — historical website tracking.
   Free, no API key required.

   Uses the CDX Server API to search historical snapshots of URLs. Track
   website changes over time: messaging pivots, product launches, team-page
   changes, pricing changes, etc. Built over the sandbox `http`/`json`/`html`
   primitives + `dvergr.intake.core` — no host libs."
  (:require [babashka.http-client :as http] [cheshire.core :as json] [dvergr.codec :as codec] [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [clojure.string :as str]))

(def CdxSnapshot
  "One CDX index row as `search-snapshots` returns it: the CDX JSON header
   fields keywordized, every value a string (timestamp YYYYMMDDHHMMSS)."
  [:map [:urlkey :string] [:timestamp :string] [:original :string] [:mimetype :string]
   [:statuscode :string] [:digest :string] [:length :string]])

(def Availability
  "The closest archived snapshot of a URL, or `{:available false :url ...}`."
  [:multi {:dispatch :available}
   [true [:map [:available [:= true]] [:url :string] [:timestamp [:maybe :string]]
          [:snapshot-url [:maybe schema/Url]] [:status [:maybe :string]]]]
   [false [:map [:available [:= false]] [:url :string]]]])

(def Snapshot
  "The text content of one archived snapshot as `fetch-snapshot` returns it."
  [:map [:url :string] [:timestamp :string] [:snapshot-url schema/Url] [:text :string]
   [:title [:maybe :string]]])

(def Version
  "One unique page version as `track-changes` returns it."
  [:map [:timestamp :string] [:snapshot-url schema/Url] [:digest :string]
   [:mime-type :string] [:length :string]])

(def ^:private cdx-base "https://web.archive.org/cdx/search/cdx")
(def ^:private availability-base "https://archive.org/wayback/available")
(def ^:private wb-ua "dvergr/1.0 (contact@replikativ.io)")

(defn search-snapshots
  "Search the Wayback Machine CDX index for snapshots of a URL.
   Returns raw CDX rows [{:urlkey :timestamp :original :mimetype :statuscode
   :digest :length}], every value a string.

   Options:
   - :from     — start date (YYYYMMDD or YYYY)
   - :to       — end date
   - :count    — max results
   - :collapse — collapse by field (e.g. 'digest' to dedupe identical pages)
   - :filter   — status code filter (e.g. 'statuscode:200')"
  {:malli/schema [:=> [:cat :string (schema/kwargs :from :string :to :string :count :int :collapse :string :filter :string)]
                  (schema/result CdxSnapshot)]}
  [url & {:keys [from to count collapse filter]
          :or {count 50}}]
  (let [params (cond-> {:url url
                        :output "json"
                        :limit count}
                 from (assoc :from from)
                 to (assoc :to to)
                 collapse (assoc :collapse collapse)
                 filter (assoc :filter filter))
        data (try
               (let [resp (http/get cdx-base
                            {:query-params params
                             :headers {"User-Agent" wb-ua}
                             :timeout 30000})
                     b    (:body resp)
                     parsed (if (string? b) (json/decode b true) b)]
                 (if (seq parsed) parsed []))
               (catch Throwable e
                 {:error (or (some->> (:status (ex-data e)) (str "HTTP "))
                             (.getMessage e))}))]
    (if (and (map? data) (:error data))
      data
      (let [;; First row is the header
            header (first data)
            rows (rest data)]
        (->> rows
             (mapv (fn [row]
                     (zipmap (map keyword header) row))))))))

(defn check-availability
  "Check if a specific URL has been archived and get the closest snapshot.
   Returns {:available :url :timestamp :snapshot-url} or {:available false}."
  {:malli/schema [:=> [:cat :string (schema/kwargs :timestamp :string)] (schema/one Availability)]}
  [url & {:keys [timestamp]}]
  (let [params (cond-> {:url url}
                 timestamp (assoc :timestamp timestamp))
        data (intake/fetch-json availability-base :query-params params)]
    (if (:error data)
      data
      (let [snap (get-in data [:archived_snapshots :closest])]
        (if snap
          {:available true
           :url url
           :timestamp (:timestamp snap)
           :snapshot-url (:url snap)
           :status (:status snap)}
          {:available false :url url})))))

(defn fetch-snapshot
  "Fetch the content of a specific Wayback Machine snapshot.
   timestamp format: YYYYMMDDHHMMSS.
   Returns {:url :timestamp :text :title} or {:error}."
  {:malli/schema [:=> [:cat :string :string (schema/kwargs :max-chars :int)] (schema/one Snapshot)]}
  [url timestamp & {:keys [max-chars] :or {max-chars 8000}}]
  (let [snapshot-url (str "https://web.archive.org/web/" timestamp "/" url)]
    (try
      (let [resp (http/get snapshot-url
                   {:headers {"User-Agent" wb-ua}
                    :timeout 30000})
            raw  (:body resp)
            body (if (string? raw) raw (json/encode raw))
            ;; Strip Wayback Machine toolbar injection, then HTML markup
            clean (str/replace body
                               #"(?s)<!-- BEGIN WAYBACK TOOLBAR INSERT -->.*?<!-- END WAYBACK TOOLBAR INSERT -->"
                               "")
            text (-> clean
                     codec/strip-tags
                     codec/decode-entities
                     (str/replace #"\s+" " ")
                     str/trim)
            text (if (> (count text) max-chars)
                   (str (subs text 0 max-chars) "\n[truncated]")
                   text)
            title (some-> (re-find #"(?i)<title[^>]*>([^<]+)</title>" body) second str/trim)]
        {:url url
         :timestamp timestamp
         :snapshot-url snapshot-url
         :text text
         :title title})
      (catch Throwable e
        {:error (or (some->> (:status (ex-data e)) (str "HTTP "))
                    (.getMessage e))
         :url snapshot-url}))))

(defn track-changes
  "Get a timeline of how a URL changed over time.
   Uses digest-based deduplication to only show unique page versions.
   Returns [{:timestamp :snapshot-url :digest :mime-type :length}] — one per unique version."
  {:malli/schema [:=> [:cat :string (schema/kwargs :from :string :to :string :count :int)] (schema/result Version)]}
  [url & {:keys [from to count]
          :or {count 30}}]
  (let [snapshots (search-snapshots url
                    :from from :to to :count count
                    :collapse "digest"
                    :filter "statuscode:200")]
    (if (:error snapshots)
      snapshots
      (->> snapshots
           (mapv (fn [s]
                   {:timestamp    (:timestamp s)
                    :snapshot-url (str "https://web.archive.org/web/" (:timestamp s) "/" url)
                    :digest       (:digest s)
                    :mime-type    (:mimetype s)
                    :length       (:length s)}))))))
