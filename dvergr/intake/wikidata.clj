(ns dvergr.intake.wikidata
  "Wikidata SPARQL intake — structured entity relationships.
   Free, no API key required. Rate limit: reasonable use.

   Wikidata is a knowledge graph with structured facts about entities.
   Key properties for company intelligence:
   - P355: subsidiary
   - P749: parent organization
   - P452: industry
   - P159: headquarters
   - P169: CEO
   - P112: founded by
   - P856: website
   - P1128: employees
   - P2139: revenue
   - P571: inception date
   - P414: stock exchange
   - P249: ticker symbol"
  (:require [dvergr.codec :as codec] [dvergr.intake.core :as intake]
            [clojure.string :as str]))

(def ^:private sparql-endpoint "https://query.wikidata.org/sparql")

(defn- sparql-query
  "Execute a SPARQL query against Wikidata. Returns parsed results."
  [query]
  (let [url (str sparql-endpoint "?query=" (codec/url-encode query) "&format=json")
        data (intake/fetch-json url
               :headers {"User-Agent" "dvergr/1.0 (contact@replikativ.io)"
                         "Accept" "application/sparql-results+json"})]
    (if (:error data)
      data
      (get-in data [:results :bindings] []))))

(defn- extract-value
  "Extract the value from a SPARQL binding."
  [binding key]
  (get-in binding [(keyword key) :value]))

(defn search-entities
  "Search Wikidata for entities by name.
   Returns [{:id :label :description :url}]."
  [query & {:keys [count type] :or {count 10}}]
  (let [url "https://www.wikidata.org/w/api.php"
        params (cond-> {:action "wbsearchentities"
                        :search query
                        :language "en"
                        :format "json"
                        :limit count}
                 type (assoc :type type))
        data (intake/fetch-json url :query-params params)]
    (if (:error data)
      data
      (->> (get data :search [])
           (mapv (fn [e]
                   {:id          (:id e)
                    :label       (:label e)
                    :description (:description e)
                    :url         (:concepturi e)}))))))

(defn fetch-company-profile
  "Fetch structured company data from Wikidata by entity ID (e.g. 'Q312' for Apple).
   Returns key facts: name, industry, HQ, CEO, founded, website, employees, etc."
  [entity-id]
  (let [q (str "SELECT ?propLabel ?valueLabel ?value WHERE {
  VALUES ?prop { wdt:P749 wdt:P452 wdt:P159 wdt:P169 wdt:P112 wdt:P856
                 wdt:P1128 wdt:P571 wdt:P414 wdt:P249 wdt:P17 wdt:P740
                 wdt:P127 wdt:P355 wdt:P1056 wdt:P154 wdt:P2139 }
  wd:" entity-id " ?prop ?value .
  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". }
}")
        results (sparql-query q)]
    (if (:error results)
      results
      (->> results
           (mapv (fn [b]
                   {:property (extract-value b "propLabel")
                    :value    (extract-value b "valueLabel")
                    :raw      (extract-value b "value")}))))))

(defn fetch-subsidiaries
  "Get all subsidiaries of a company from Wikidata."
  [entity-id]
  (let [q (str "SELECT ?subsidiary ?subsidiaryLabel ?countryLabel WHERE {
  ?subsidiary wdt:P749 wd:" entity-id " .
  OPTIONAL { ?subsidiary wdt:P17 ?country . }
  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". }
}")
        results (sparql-query q)]
    (if (:error results)
      results
      (->> results
           (mapv (fn [b]
                   {:id      (last (str/split (extract-value b "subsidiary") #"/"))
                    :name    (extract-value b "subsidiaryLabel")
                    :country (extract-value b "countryLabel")}))))))

(defn fetch-competitors
  "Find companies in the same industry as the given entity."
  [entity-id & {:keys [count] :or {count 30}}]
  (let [q (str "SELECT DISTINCT ?company ?companyLabel ?industryLabel WHERE {
  wd:" entity-id " wdt:P452 ?industry .
  ?company wdt:P452 ?industry .
  ?company wdt:P31/wdt:P279* wd:Q4830453 .
  FILTER(?company != wd:" entity-id ")
  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". }
} LIMIT " count)
        results (sparql-query q)]
    (if (:error results)
      results
      (->> results
           (mapv (fn [b]
                   {:id       (last (str/split (extract-value b "company") #"/"))
                    :name     (extract-value b "companyLabel")
                    :industry (extract-value b "industryLabel")}))))))

(defn fetch-industry-companies
  "Find all companies in a specific industry by Wikidata industry entity ID.
   Example industries: Q80228 (cloud computing), Q11661 (IT), Q7397 (software)."
  [industry-id & {:keys [count] :or {count 50}}]
  (let [q (str "SELECT ?company ?companyLabel ?countryLabel ?employeesLabel WHERE {
  ?company wdt:P452 wd:" industry-id " .
  ?company wdt:P31/wdt:P279* wd:Q4830453 .
  OPTIONAL { ?company wdt:P17 ?country . }
  OPTIONAL { ?company wdt:P1128 ?employees . }
  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". }
} LIMIT " count)
        results (sparql-query q)]
    (if (:error results)
      results
      (->> results
           (mapv (fn [b]
                   {:id        (last (str/split (extract-value b "company") #"/"))
                    :name      (extract-value b "companyLabel")
                    :country   (extract-value b "countryLabel")
                    :employees (extract-value b "employeesLabel")}))))))

(defn custom-sparql
  "Execute an arbitrary SPARQL query against Wikidata.
   Returns raw bindings as maps."
  [query]
  (let [results (sparql-query query)]
    (if (:error results)
      results
      (->> results
           (mapv (fn [b]
                   (into {}
                     (map (fn [[k v]] [(name k) (:value v)])
                          b))))))))
