(ns dvergr.intake.shapes-a-test
  "Fixture checks: the pure shaping fns produce data matching their schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [dvergr.intake.schema :as schema]
            [dvergr.intake.core :as intake]
            [dvergr.intake.adzuna :as adzuna]
            [dvergr.intake.arxiv :as arxiv]
            [dvergr.intake.bluesky :as bluesky]
            [dvergr.intake.companies-house :as ch]
            [dvergr.intake.crt-sh :as crt]
            [dvergr.intake.devto :as devto]
            [dvergr.intake.evidence :as evidence]
            [dvergr.intake.finnhub :as finnhub]))

(defn- valid [schema x]
  (or (m/validate schema x) (me/humanize (m/explain schema x))))

;; adzuna, companies-house, crt-sh and finnhub shape inline in the public fns,
;; so those fixtures stub the HTTP call (`with-redefs`) — no network.

(deftest adzuna-shapes-match
  (with-redefs [adzuna/adzuna-get
                (fn [path & _]
                  (cond
                    (re-find #"/search/" path)
                    {:results [{:title "Clojure Dev" :redirect_url "https://adzuna.com/x/1"
                                :created "2026-09-01T10:00:00Z"
                                :company {:display_name "Acme"}
                                :location {:display_name ["UK" "London"]}
                                :salary_min 50000 :salary_max 70000.5
                                :description (apply str (repeat 400 "x"))
                                :category {:label "IT Jobs"} :contract_type "permanent"}
                               {:title "Bare" :redirect_url nil :created nil}]}
                    (re-find #"/history" path) {:month {:2026-02 81000.4 :2026-01 80000 :2026-03 nil}}
                    (re-find #"/top_companies" path) {:leaderboard [{:canonical_name "Acme" :count 12}]}))]
    (let [jobs (adzuna/search-jobs "clojure")]
      (is (true? (valid (schema/result adzuna/Job) jobs)))
      (is (= "UK, London" (:location (first jobs)))))
    (is (true? (valid (schema/one adzuna/SalaryHistory) (adzuna/salary-history "clojure"))))
    (is (true? (valid (schema/result adzuna/CompanyCount) (adzuna/top-companies "clojure"))))
    (is (true? (valid (schema/one adzuna/CompanyJobs) (adzuna/company-jobs "Acme")))))
  (is (not (m/validate adzuna/Job {:title "T" :url "u" :created nil :salary-min "lots"})) "not vacuous"))

;; No xmlns on <feed>: the sandbox's xml/parse-str keys tags by qname (:entry),
;; babashka's clojure.data.xml would namespace-qualify them.
(def ^:private arxiv-feed
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<feed>
  <entry>
    <id>http://arxiv.org/abs/2303.08774v6</id>
    <updated>2024-03-04T06:01:25Z</updated>
    <published>2023-03-15T17:15:04Z</published>
    <title>GPT-4 Technical
      Report</title>
    <summary>  We report the development of GPT-4.  </summary>
    <author><name>OpenAI</name></author>
    <author><name>Josh Achiam</name></author>
    <link href=\"http://arxiv.org/abs/2303.08774v6\" rel=\"alternate\" type=\"text/html\"/>
    <link title=\"pdf\" href=\"http://arxiv.org/pdf/2303.08774v6\" rel=\"related\" type=\"application/pdf\"/>
    <category term=\"cs.CL\" scheme=\"http://arxiv.org/schemas/atom\"/>
  </entry>
  <entry><id>http://arxiv.org/abs/1234.5678</id></entry>
</feed>")

(deftest arxiv-shapes-match
  (let [papers (#'arxiv/parse-feed arxiv-feed)]
    (is (= 2 (count papers)))
    (is (= "GPT-4 Technical Report" (:title (first papers))))
    (is (= ["OpenAI" "Josh Achiam"] (:authors (first papers))))
    (is (true? (valid (schema/result arxiv/Paper) papers)))
    (is (true? (valid (schema/result arxiv/Paper) (#'arxiv/parse-feed {:error "HTTP 503"})))))
  (is (not (m/validate arxiv/Paper {:id "x" :authors "someone"})) "not vacuous"))

(deftest bluesky-shapes-match
  (let [post {:uri "at://did:plc:abc123/app.bsky.feed.post/3kxyz"
              :record {:text "Hello from the atmosphere"}
              :likeCount 5 :repostCount 2 :replyCount 1}
        p (#'bluesky/parse-post post)]
    (is (= "https://bsky.app/profile/did:plc:abc123/post/3kxyz" (:url p)))
    (is (true? (valid bluesky/Post p)))
    (is (true? (valid bluesky/Post (#'bluesky/parse-post {:record {}}))))
    (is (not (m/validate bluesky/Post (assoc p :source :hn))) "not vacuous")))

(deftest companies-house-shapes-match
  (with-redefs [ch/api-key (constantly "a2V5Og==")
                intake/fetch-json
                (fn [url & _]
                  (cond
                    (re-find #"/search/companies" url)
                    {:items [{:company_number "00000006" :title "MARINE AND GENERAL MUTUAL LIFE"
                              :company_status "active" :company_type "ltd"
                              :date_of_creation "1862-10-25"
                              :address {:premises "14" :address_line_1 "Cannon Street"
                                        :locality "London" :postal_code "EC4M 6XH"}}
                             {}]}
                    (re-find #"/officers" url)
                    {:items [{:name "DOE, Jane" :officer_role "director" :appointed_on "2020-01-01"
                              :nationality "British" :occupation "Engineer"
                              :country_of_residence "England"}]}
                    (re-find #"/filing-history" url)
                    {:items [{:date "2024-05-01" :category "accounts" :type "AA"
                              :description "accounts-with-accounts-type-full"
                              :links {:self "/company/00000006/filing-history/MzA"
                                      :document_metadata "https://frontend-doc-api.company-information.service.gov.uk/document/abc"}}
                             {:date "2023-06-01" :links {:document_metadata "/document/rel"}}
                             {:date "2023-05-01"}]}
                    (re-find #"/persons-with-significant-control" url)
                    {:items [{:name "Mr John Smith" :kind "individual-person-with-significant-control"
                              :natures_of_control ["ownership-of-shares-75-to-100-percent"]
                              :notified_on "2016-04-06" :nationality "British"
                              :country_of_residence "England"
                              :name_elements {:forename "John" :surname "Smith" :title "Mr"}}]}
                    :else
                    {:company_number "00000006" :company_name "MARINE AND GENERAL MUTUAL LIFE"
                     :company_status "active" :type "ltd" :date_of_creation "1862-10-25"
                     :sic_codes ["65110"]
                     :registered_office_address {:address_line_1 "Cannon Street" :locality "London"}
                     :accounts {:next_due "2025-09-30"} :confirmation_statement {:next_due "2025-06-01"}
                     :jurisdiction "england-wales" :has_charges false :has_insolvency_history false}))]
    (is (true? (valid (schema/result ch/CompanySummary) (ch/search-companies "marine"))))
    (is (true? (valid (schema/one ch/Company) (ch/fetch-company "00000006"))))
    (is (true? (valid (schema/result ch/Officer) (ch/fetch-officers "00000006"))))
    (let [filings (ch/fetch-filing-history "00000006")]
      (is (true? (valid (schema/result ch/Filing) filings)))
      (is (= ["https://frontend-doc-api.company-information.service.gov.uk/document/abc"
              "https://find-and-update.company-information.service.gov.uk/document/rel"
              nil]
             (map :url filings))
          "absolute document_metadata used as-is, relative prefixed"))
    (is (true? (valid (schema/result ch/Psc) (ch/fetch-persons-significant-control "00000006")))))
  (is (not (m/validate ch/Company {:company-number "1"})) "not vacuous"))

(deftest crt-sh-shapes-match
  (with-redefs [intake/fetch-json
                (fn [& _]
                  [{:id 12345678 :issuer_name "C=US, O=Let's Encrypt, CN=R3"
                    :name_value "api.example.com\nblog.example.com\n*.example.com"
                    :common_name "api.example.com" :not_before "2026-01-01T00:00:00"
                    :not_after "2026-04-01T00:00:00" :serial_number "04ab"
                    :entry_timestamp "2026-01-01T01:02:03.456"}
                   {:id 2 :name_value "Staging.Example.com"}])]
    (is (true? (valid (schema/result crt/Certificate) (crt/search-certificates "%.example.com"))))
    (is (true? (valid (schema/result :string) (crt/discover-subdomains "example.com"))))
    (let [a (crt/analyze-subdomains "example.com")]
      (is (true? (valid (schema/one crt/SubdomainAnalysis) a)))
      (is (= ["staging.example.com"] (get-in a [:categories :staging])))))
  (is (not (m/validate crt/SubdomainAnalysis {:domain "d" :total 0 :subdomains []
                                              :categories {:bogus []}}))
      "not vacuous"))

(deftest devto-shapes-match
  (let [article {:title "Clojure in 2026" :url "https://dev.to/a/clojure-2026"
                 :public_reactions_count 42 :comments_count 7
                 :tag_list ["clojure" "lisp"] :description "Why."}]
    (is (true? (valid devto/Article (#'devto/parse-article article))))
    (is (= ["clojure" "lisp"] (:tags (#'devto/parse-article (assoc article :tag_list "clojure, lisp")))))
    (is (true? (valid devto/Article (#'devto/parse-article {:tag_list "clojure, lisp"}))))
    (is (not (m/validate devto/Article (#'devto/parse-article (assoc article :comments_count "7"))))
        "not vacuous")))

(deftest evidence-shapes-match
  (let [response {:url "https://example.org/source" :status 200 :body "Alpha supports shared work."
                  :dvergr/acquisition {:id (random-uuid) :capture :captured}
                  :dvergr/fixture-id (random-uuid)}]
    (is (true? (valid evidence/Quote (evidence/quote-span response :body 6 26))))
    (is (true? (valid evidence/Quote (evidence/quote-span (dissoc response :url :dvergr/fixture-id)
                                                          :body 0 5))))
    (is (not (m/validate evidence/Quote (dissoc (evidence/quote-span response :body 6 26)
                                                :dvergr/acquisition)))
        "not vacuous")))

(deftest finnhub-shapes-match
  (with-redefs [finnhub/finnhub-get
                (fn [path & _]
                  (case path
                    "/quote" {:c 189.84 :h 190.32 :l 188.19 :o 189.33 :pc 188.63 :d 1.21
                              :dp 0.6415 :t 1726776000}
                    "/stock/profile2" {:name "Apple Inc" :country "US" :exchange "NASDAQ"
                                       :finnhubIndustry "Technology" :marketCapitalization 2950000
                                       :shareOutstanding 15204.14 :ipo "1980-12-12"
                                       :logo "https://static.finnhub.io/logo/aapl.png"
                                       :weburl "https://www.apple.com/" :ticker "AAPL" :currency "USD"}
                    "/stock/earnings" [{:period "2026-06-30" :actual 1.4 :estimate 1.35 :surprise 0.05
                                        :surprisePercent 3.7 :symbol "AAPL"}
                                       {:period "2026-03-31" :actual nil :estimate 1 :surprise nil
                                        :surprisePercent nil :symbol "AAPL"}]
                    "/company-news" [{:headline "Apple ships" :summary "..." :source "Reuters"
                                      :url "https://example.com/n" :datetime 1726776000
                                      :category "company"}]
                    "/stock/insider-transactions" {:data [{:name "COOK TIMOTHY D" :share 3280050
                                                           :change -59751 :transactionPrice 224.5
                                                           :transactionCode "S" :filingDate "2026-04-03"}]}
                    "/stock/peers" ["AAPL" "DELL" "HPQ"]
                    "/stock/metric" {:metric {:peBasicExclExtraTTM 29.4 :beta 1.24 :52WeekHigh 237.23
                                              :marketCapitalization 2950000
                                              :currentEv/freeCashFlowAnnual 31.2}}))]
    (is (true? (valid (schema/one finnhub/Quote) (finnhub/fetch-quote "aapl"))))
    (is (true? (valid (schema/one finnhub/Profile) (finnhub/fetch-company-profile "aapl"))))
    (is (true? (valid (schema/result finnhub/Earning) (finnhub/fetch-earnings "aapl"))))
    (is (true? (valid (schema/result finnhub/NewsArticle) (finnhub/fetch-company-news "aapl"))))
    (let [txs (finnhub/fetch-insider-transactions "aapl")]
      (is (true? (valid (schema/result finnhub/InsiderTransaction) txs)))
      (is (= "S" (:transaction-type (first txs))) "read from transactionCode"))
    (is (true? (valid (schema/result :string) (finnhub/fetch-peers "aapl"))))
    (let [fin (finnhub/fetch-basic-financials "aapl")]
      (is (true? (valid (schema/one finnhub/BasicFinancials) fin)))
      (is (= 31.2 (:ev-fcf fin)))
      (is (not (contains? fin :ev-ebitda)))))
  (is (not (m/validate finnhub/Quote {:current "189"})) "not vacuous"))
