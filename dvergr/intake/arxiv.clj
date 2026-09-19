(ns dvergr.intake.arxiv
  "arXiv via the public API (no auth). Returns Atom XML parsed into paper maps.
   Demonstrates parsing a feed with the `xml` primitive: `(xml/parse-str s)` returns
   a {:tag :attrs :content} tree, `(xml/text node)` flattens its text. No SAX,
   no host libs — agent-readable + extendable."
  (:require [clojure.data.xml :as xml] [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [clojure.string :as str]))

(def ^:private api-base "https://export.arxiv.org/api/query")

(def Paper
  "One arXiv paper parsed from an Atom <entry>; dates are yyyy-MM-dd."
  [:map [:id :string] [:title [:maybe :string]] [:summary [:maybe :string]]
   [:abs-url schema/Url] [:pdf-url [:maybe schema/Url]]
   [:published [:maybe schema/IsoDate]] [:updated [:maybe schema/IsoDate]]
   [:authors [:vector :string]] [:categories [:vector :string]]])

(defn- elems [node tag] (filter #(and (map? %) (= tag (:tag %))) (:content node)))
(defn- elem  [node tag] (first (elems node tag)))
(defn- etext [node] (some-> (xml/text node) str/trim))
(defn- take10 [s] (when s (subs s 0 (min 10 (count s)))))

(defn- parse-entry [entry]
  (let [id-raw  (str/replace (or (etext (elem entry :id)) "") #"^https?://arxiv\.org/abs/" "")
        links   (elems entry :link)
        abs-url (some #(when (= "alternate" (get-in % [:attrs :rel])) (get-in % [:attrs :href])) links)
        pdf-url (some #(when (= "pdf" (get-in % [:attrs :title]))    (get-in % [:attrs :href])) links)
        authors (->> (elems entry :author) (map #(etext (elem % :name))) (remove nil?) vec)
        cats    (->> (elems entry :category) (map #(get-in % [:attrs :term])) (remove nil?) vec)
        summary (some-> (etext (elem entry :summary)) (str/replace #"\s+" " "))]
    {:id         id-raw
     :title      (some-> (etext (elem entry :title)) (str/replace #"\s+" " "))
     :summary    (when summary (if (> (count summary) 500) (str (subs summary 0 500) "...") summary))
     :abs-url    (or abs-url (str "https://arxiv.org/abs/" id-raw))
     :pdf-url    pdf-url
     :published  (take10 (etext (elem entry :published)))
     :updated    (take10 (etext (elem entry :updated)))
     :authors    authors
     :categories cats}))

(defn- parse-feed [body]
  (if (and (map? body) (:error body))
    body
    (let [feed (try (xml/parse-str body)
                    (catch Throwable e {:error (str "XML parse error: " (.getMessage e))}))]
      (if (and (map? feed) (:error feed))
        feed
        (mapv parse-entry (elems feed :entry))))))

(defn search-papers
  "Search arXiv papers. kwargs: :count (≤100) :sort-by (relevance|lastUpdatedDate|
   submittedDate) :sort-order (descending|ascending) :start. Vector of maps or {:error}."
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int :sort-by :string :sort-order :string :start :int)] (schema/result Paper)]}
  [query & {:keys [count sort-by sort-order start]
            :or {count 10 sort-by "relevance" sort-order "descending" start 0}}]
  (parse-feed (intake/fetch-text api-base
                                 :query-params {:search_query query
                                                :max_results  (min count 100)
                                                :sortBy       sort-by
                                                :sortOrder    sort-order
                                                :start        start})))

(defn fetch-paper
  "Fetch a single arXiv paper by id (e.g. 2303.08774). One map or {:error}."
  {:malli/schema [:=> [:cat :string] (schema/one [:maybe Paper])]}
  [arxiv-id]
  (let [clean-id (str/replace arxiv-id #"^https?://arxiv\.org/abs/" "")
        parsed   (parse-feed (intake/fetch-text api-base
                                                :query-params {:id_list clean-id :max_results 1}))]
    (if (and (map? parsed) (:error parsed)) parsed (first parsed))))
