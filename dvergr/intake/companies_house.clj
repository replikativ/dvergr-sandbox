(ns dvergr.intake.companies-house
  "UK Companies House intake — company data, directors, filings.
   Free API key required: https://developer.company-information.service.gov.uk/
   Rate limit: 600 requests per 5 minutes.
   Set COMPANIES_HOUSE_API_KEY env var."
  (:require [dvergr.codec :as codec] [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [clojure.string :as str]))

(def ^:private api-base "https://api.company-information.service.gov.uk")

(def CompanySummary
  "One company search hit; :address joins the present address parts."
  [:map [:company-number [:maybe :string]] [:title [:maybe :string]] [:status [:maybe :string]]
   [:type [:maybe :string]] [:date-of-creation [:maybe schema/IsoDate]] [:address :string]])

(def Company
  "Full company details; :accounts and :confirmation-statement are the raw API maps."
  [:map [:company-number [:maybe :string]] [:name [:maybe :string]] [:status [:maybe :string]]
   [:type [:maybe :string]] [:created [:maybe schema/IsoDate]] [:sic-codes [:maybe [:sequential :string]]]
   [:address :string] [:accounts [:maybe :map]] [:confirmation-statement [:maybe :map]]
   [:jurisdiction [:maybe :string]] [:has-charges [:maybe :boolean]]
   [:has-insolvency-history [:maybe :boolean]]])

(def Officer
  "One company officer (director, secretary, ...)."
  [:map [:name [:maybe :string]] [:role [:maybe :string]] [:appointed [:maybe schema/IsoDate]]
   [:resigned [:maybe schema/IsoDate]] [:nationality [:maybe :string]]
   [:occupation [:maybe :string]] [:country [:maybe :string]]])

(def Filing
  "One filing-history entry."
  [:map [:date [:maybe schema/IsoDate]] [:category [:maybe :string]] [:type [:maybe :string]]
   [:description [:maybe :string]] [:url [:maybe schema/Url]]])

(def Psc
  "One person with significant control; :name-elements is the raw API map."
  [:map [:name [:maybe :string]] [:kind [:maybe :string]]
   [:natures-of-control [:maybe [:sequential :string]]] [:notified-on [:maybe schema/IsoDate]]
   [:nationality [:maybe :string]] [:country [:maybe :string]] [:name-elements [:maybe :map]]])

;; Companies House uses HTTP Basic Auth with the API key as username (no password).
;; `(env/get "COMPANIES_HOUSE_AUTH")` returns the PRE-ENCODED `base64(key:)` — for a
;; real deployment it's an opaque placeholder substituted by the gated `http`
;; primitive at egress (boundary secret injection), so the key is never in the
;; sandbox. Configure it as a `:secrets` entry with
;; `:basic-auth-config-paths [[:companies-house :key]]`.
(defn- api-key [] (env/get "COMPANIES_HOUSE_AUTH"))

(defn- auth-headers []
  (when-let [a (api-key)]
    {"Authorization" (str "Basic " a)}))

(defn search-companies
  "Search for UK companies by name.
   Returns [{:company-number :title :status :address :type :date-of-creation}]."
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int)] (schema/result CompanySummary)]}
  [query & {:keys [count] :or {count 10}}]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set. Get a free key at https://developer.company-information.service.gov.uk/"}
    (let [data (intake/fetch-json (str api-base "/search/companies")
                                  :headers (auth-headers)
                                  :query-params {:q query
                                                 :items_per_page count})]
      (if (:error data)
        data
        (->> (get data :items [])
             (mapv (fn [item]
                     {:company-number (:company_number item)
                      :title          (:title item)
                      :status         (:company_status item)
                      :type           (:company_type item)
                      :date-of-creation (:date_of_creation item)
                      :address        (let [a (:address item)]
                                        (str/join ", " (remove nil? [(:premises a) (:address_line_1 a)
                                                                      (:locality a) (:postal_code a)])))})))))))

(defn fetch-company
  "Get full details for a UK company by company number."
  {:malli/schema [:=> [:cat :string] (schema/one Company)]}
  [company-number]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set"}
    (let [data (intake/fetch-json (str api-base "/company/" company-number)
                                  :headers (auth-headers))]
      (if (:error data)
        data
        {:company-number (:company_number data)
         :name           (:company_name data)
         :status         (:company_status data)
         :type           (:type data)
         :created        (:date_of_creation data)
         :sic-codes      (:sic_codes data)
         :address        (let [a (:registered_office_address data)]
                           (str/join ", " (remove nil? [(:address_line_1 a) (:address_line_2 a)
                                                         (:locality a) (:region a) (:postal_code a)
                                                         (:country a)])))
         :accounts       (:accounts data)
         :confirmation-statement (:confirmation_statement data)
         :jurisdiction   (:jurisdiction data)
         :has-charges    (:has_charges data)
         :has-insolvency-history (:has_insolvency_history data)}))))

(defn fetch-officers
  "Get officers (directors, secretaries) for a UK company."
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int)] (schema/result Officer)]}
  [company-number & {:keys [count] :or {count 20}}]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set"}
    (let [data (intake/fetch-json (str api-base "/company/" company-number "/officers")
                                  :headers (auth-headers)
                                  :query-params {:items_per_page count})]
      (if (:error data)
        data
        (->> (get data :items [])
             (mapv (fn [officer]
                     {:name        (:name officer)
                      :role        (:officer_role officer)
                      :appointed   (:appointed_on officer)
                      :resigned    (:resigned_on officer)
                      :nationality (:nationality officer)
                      :occupation  (:occupation officer)
                      :country     (get-in officer [:country_of_residence])})))))))

(defn fetch-filing-history
  "Get filing history for a UK company."
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int :category :string)] (schema/result Filing)]}
  [company-number & {:keys [count category]
                     :or {count 10}}]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set"}
    (let [params (cond-> {:items_per_page count}
                   category (assoc :category category))
          data (intake/fetch-json (str api-base "/company/" company-number "/filing-history")
                                  :headers (auth-headers)
                                  :query-params params)]
      (if (:error data)
        data
        (->> (get data :items [])
             (mapv (fn [filing]
                     {:date        (:date filing)
                      :category    (:category filing)
                      :type        (:type filing)
                      :description (:description filing)
                      :url         (when-let [links (:links filing)]
                                     (str "https://find-and-update.company-information.service.gov.uk"
                                          (:document_metadata links)))})))))))

(defn fetch-persons-significant-control
  "Get persons with significant control (PSC) — beneficial owners."
  {:malli/schema [:=> [:cat :string] (schema/result Psc)]}
  [company-number]
  (if-not (api-key)
    {:error "COMPANIES_HOUSE_API_KEY not set"}
    (let [data (intake/fetch-json (str api-base "/company/" company-number
                                       "/persons-with-significant-control")
                                  :headers (auth-headers))]
      (if (:error data)
        data
        (->> (get data :items [])
             (mapv (fn [psc]
                     {:name               (:name psc)
                      :kind               (:kind psc)
                      :natures-of-control (:natures_of_control psc)
                      :notified-on        (:notified_on psc)
                      :nationality        (:nationality psc)
                      :country            (:country_of_residence psc)
                      :name-elements      (:name_elements psc)})))))))
