(ns dvergr.intake.adzuna
  "Adzuna job market API — search jobs, salary history, top companies.
   Free tier: 250 requests/day across 16+ countries.
   Set ADZUNA_APP_ID and ADZUNA_APP_KEY env vars.
   Register at https://developer.adzuna.com/

   Public fns return RAW data (maps / vectors of maps, or {:error \"…\"})."
  (:require [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [clojure.string :as str]))

(def ^:private api-base "https://api.adzuna.com/api/v1")

(def ^:private Num "A JSON number." [:or :int :double])

(def Job
  "One job ad as `search-jobs` returns it; keys other than :title :url :created
   appear only when the API sent the field."
  [:map [:title [:maybe :string]] [:url [:maybe schema/Url]] [:created [:maybe schema/IsoDate]]
   [:company {:optional true} [:maybe :string]] [:location {:optional true} :string]
   [:salary-min {:optional true} Num] [:salary-max {:optional true} Num]
   [:description {:optional true} :string] [:category {:optional true} [:maybe :string]]
   [:contract-type {:optional true} :string]])

(def SalaryHistory
  "Monthly average salaries, sorted by month (\"yyyy-MM\")."
  [:map [:months [:vector [:map [:month :string] [:salary [:maybe :int]]]]]])

(def CompanyCount
  "One entry of the top-companies leaderboard."
  [:map [:company-name [:maybe :string]] [:count [:maybe :int]]])

(def CompanyJobs
  "Jobs at one company, as `company-jobs` returns them."
  [:map [:company :string] [:total-count :int] [:jobs [:vector Job]]])

(defn- app-id [] (env/get "ADZUNA_APP_ID"))
(defn- app-key [] (env/get "ADZUNA_APP_KEY"))

(defn- adzuna-get
  "GET from Adzuna API with credentials."
  [path & {:keys [params]}]
  (if-not (and (app-id) (app-key))
    {:error "ADZUNA_APP_ID and ADZUNA_APP_KEY not set. Register at https://developer.adzuna.com/"}
    (intake/fetch-json (str api-base path)
                       :query-params (merge {:app_id (app-id)
                                             :app_key (app-key)}
                                            (or params {})))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn search-jobs
  "Search for jobs on Adzuna.

   Args:
     query - Search keywords (e.g. \"clojure developer\")

   Options:
     :country          - ISO country code (default \"us\")
     :location         - Location filter (e.g. \"London\", \"San Francisco\")
     :company          - Company name filter
     :category         - Job category (e.g. \"it-jobs\", \"engineering-jobs\")
     :salary-min       - Minimum salary
     :results-per-page - Results per page (default 10, max 50)
     :page             - Page number (default 1)
     :sort-by          - Sort: \"date\", \"salary\", \"relevance\" (default \"date\")

   Returns: [{:title :company :location :salary-min :salary-max
              :description :url :created :category :contract-type}]"
  {:malli/schema [:=> [:cat :string (schema/kwargs :country :string :location :string :company :string :category :string :salary-min Num :results-per-page :int :page :int :sort-by :string)] (schema/result Job)]}
  [query & {:keys [country location company category salary-min
                   results-per-page page sort-by]
            :or {country "us" results-per-page 10 page 1 sort-by "date"}}]
  (let [params (cond-> {:what query
                        :results_per_page results-per-page
                        :sort_by sort-by}
                 location   (assoc :where location)
                 company    (assoc :company company)
                 category   (assoc :category category)
                 salary-min (assoc :salary_min salary-min))
        data (adzuna-get (str "/jobs/" country "/search/" page) :params params)]
    (if (:error data)
      data
      (let [results (get data :results [])]
        (mapv (fn [job]
                (cond-> {:title    (:title job)
                         :url      (:redirect_url job)
                         :created  (:created job)}
                  (:company job)
                  (assoc :company (get-in job [:company :display_name]))
                  (:location job)
                  (assoc :location (let [loc (get-in job [:location :display_name])]
                                     (if (sequential? loc)
                                       (str/join ", " loc)
                                       (str loc))))
                  (:salary_min job) (assoc :salary-min (:salary_min job))
                  (:salary_max job) (assoc :salary-max (:salary_max job))
                  (:description job) (assoc :description (let [d (:description job)]
                                                           (if (> (count d) 300)
                                                             (str (subs d 0 300) "...")
                                                             d)))
                  (:category job) (assoc :category (get-in job [:category :label]))
                  (:contract_type job) (assoc :contract-type (:contract_type job))))
              results)))))

(defn salary-history
  "Get salary history/trends for a role or keyword.

   Args:
     query - Search keywords (e.g. \"data engineer\")

   Options:
     :country  - ISO country code (default \"us\")
     :location - Location filter
     :months   - Number of months (default 12)

   Returns: {:months [{:month \"2025-01\" :salary 85000} ...]}"
  {:malli/schema [:=> [:cat :string (schema/kwargs :country :string :location :string :months :int)] (schema/one SalaryHistory)]}
  [query & {:keys [country location months]
            :or {country "us" months 12}}]
  (let [params (cond-> {:what query
                        :months months}
                 location (assoc :where location))
        data (adzuna-get (str "/jobs/" country "/history") :params params)]
    (if (:error data)
      data
      {:months (->> (get data :month [])
                    (map (fn [[month salary]]
                           {:month (name month)
                            :salary (when salary (Math/round (double salary)))}))
                    (sort-by :month)
                    vec)})))

(defn top-companies
  "Get top companies hiring for a keyword.

   Args:
     query - Search keywords (e.g. \"kubernetes\")

   Options:
     :country  - ISO country code (default \"us\")
     :location - Location filter

   Returns: [{:company-name :count}]"
  {:malli/schema [:=> [:cat :string (schema/kwargs :country :string :location :string)] (schema/result CompanyCount)]}
  [query & {:keys [country location]
            :or {country "us"}}]
  (let [params (cond-> {:what query}
                 location (assoc :where location))
        data (adzuna-get (str "/jobs/" country "/top_companies") :params params)]
    (if (:error data)
      data
      (->> (get data :leaderboard [])
           (mapv (fn [entry]
                   {:company-name (get-in entry [:canonical_name]
                                          (get entry :canonical_name ""))
                    :count (:count entry)}))))))

(defn company-jobs
  "Search for jobs at a specific company.

   Convenience wrapper around search-jobs with company filter.

   Args:
     company-name - Company name

   Options:
     :country          - ISO country code (default \"us\")
     :results-per-page - Results per page (default 20)

   Returns: {:company :total-count :jobs [...]}"
  {:malli/schema [:=> [:cat :string (schema/kwargs :country :string :results-per-page :int)] (schema/one CompanyJobs)]}
  [company-name & {:keys [country results-per-page]
                   :or {country "us" results-per-page 20}}]
  (let [jobs (search-jobs "" :company company-name
                          :country country
                          :results-per-page results-per-page)]
    (if (:error jobs)
      jobs
      {:company company-name
       :total-count (count jobs)
       :jobs jobs})))
