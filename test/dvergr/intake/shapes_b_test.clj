(ns dvergr.intake.shapes-b-test
  "Fixture checks: the pure shaping fns produce data matching their schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [clojure.data.xml :as xml]
            [dvergr.codec :as codec]
            [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [dvergr.intake.github :as github]
            [dvergr.intake.gleif :as gleif]
            [dvergr.intake.linkedin :as linkedin]
            [dvergr.intake.lobsters :as lobsters]
            [dvergr.intake.mastodon :as mastodon]
            [dvergr.intake.rss :as rss]
            [dvergr.intake.sec-edgar :as sec]))

(defn- valid [schema x]
  (or (m/validate schema x) (me/humanize (m/explain schema x))))

(defn- fetch-stub
  "A fetch-json stand-in answering each request from `routes`, a seq of
   [url-substring response] tried in order."
  [routes]
  (fn [url & _]
    (or (some (fn [[frag resp]] (when (.contains ^String url frag) resp)) routes)
        {:error "HTTP 404"})))

;; --- github ---

(deftest github-shapes-match
  (let [repo {:full_name "a/b" :description nil :html_url "https://github.com/a/b"
              :stargazers_count 10 :forks_count 2 :language nil}
        rel  {:tag_name "v1" :html_url "https://github.com/a/b/releases/v1" :body nil}]
    (is (true? (valid github/Repo (#'github/parse-repo repo))))
    (is (true? (valid github/Repo (#'github/parse-repo (assoc repo :language "Clojure")))))
    (is (true? (valid github/Release (#'github/parse-release "a/b" rel))))
    (is (true? (valid github/Release (#'github/parse-release "a/b" (assoc rel :body (apply str (repeat 400 "x")))))))
    (with-redefs [intake/fetch-json
                  (fetch-stub [["/search/code" {:items [{:path "deps.edn" :repository {:full_name "a/b"}
                                                         :html_url "https://github.com/a/b/blob/main/deps.edn"}]}]
                               ["/search/repositories" {:items [repo]}]
                               ["/contributors" [{:login "x" :contributions 3 :html_url "u" :avatar_url nil}]]
                               ["/members" [{:login "x" :html_url "u"}]]
                               ["/issues" [{:number 1 :title "t" :state "open" :user {:login "x"}
                                            :html_url "u" :created_at "2026-01-01T00:00:00Z"
                                            :updated_at nil :comments 0 :labels [{:name "bug"}]}]]
                               ["/releases" [rel]]
                               ["/users/" {:login "x" :name nil :followers 1 :html_url "u"}]
                               ["/repos/a/b" (merge repo {:topics ["clj"] :license {:spdx_id "MIT"}
                                                          :open_issues_count 0})]])]
      (is (true? (valid (schema/result github/Repo) (github/fetch-trending :language "Clojure"))))
      (is (true? (valid (schema/result github/Repo) (github/search-repos "q"))))
      (is (true? (valid [:vector github/Release] (github/fetch-releases "a/b, c/d"))))
      (is (true? (valid (schema/one github/User) (github/fetch-user "x"))))
      (is (true? (valid (schema/result github/Contributor) (github/fetch-contributors "a/b"))))
      (is (true? (valid (schema/result github/CodeHit) (github/search-code "q"))))
      (is (true? (valid (schema/result github/Member) (github/fetch-org-members "o"))))
      (is (true? (valid (schema/result github/Issue) (github/fetch-issues "a/b"))))
      (is (true? (valid (schema/one github/RepoDetails) (github/fetch-repo-details "a/b")))))
    (is (not (m/validate github/Repo (assoc (#'github/parse-repo repo) :source :hn))) "not vacuous")))

;; --- gleif ---

(deftest gleif-shapes-match
  (let [lei "529900T8BM49AURSDO55"
        rec {:id lei
             :attributes {:entity {:legalName {:name "ACME AG"}
                                   :otherNames [{:name "Acme"}]
                                   :legalAddress {:addressLines ["Str. 1"] :city "Berlin"
                                                  :postalCode "10115" :country "DE"}
                                   :headquartersAddress {:city "Berlin" :country "DE"}
                                   :jurisdiction "DE" :legalForm {:id "8888"}
                                   :status "ACTIVE" :category "GENERAL"}
                          :registration {:initialRegistrationDate "2014-01-01T00:00:00Z"
                                         :managingLou "5299000J2N45DDNE4Y28"}}}
        rel {:attributes {:relationship {:startNode {:id "PARENTLEI"} :endNode {:id "CHILDLEI"}
                                         :type "IS_DIRECTLY_CONSOLIDATED_BY" :status "ACTIVE"}}}]
    (with-redefs [intake/fetch-json
                  (fetch-stub [["/direct-parent-relationship" {:data rel}]
                               ["/ultimate-parent-relationship" {:error "HTTP 404"}]
                               ["/direct-child-relationships" {:data [rel]}]
                               ["/lei-records/" {:data rec}]
                               ["/lei-records" {:data [rec]}]])]
      (is (true? (valid (schema/result gleif/Entity) (gleif/search-entities "acme"))))
      (is (true? (valid gleif/EntityRecord (gleif/fetch-entity lei))))
      (is (true? (valid gleif/DirectParent (gleif/fetch-direct-parent lei))))
      (is (true? (valid gleif/NoParent (gleif/fetch-ultimate-parent lei))))
      (is (true? (valid (schema/result gleif/Child) (gleif/fetch-children lei))))
      (is (true? (valid gleif/CorporateTree (gleif/map-corporate-tree lei))))
      (is (not (m/validate gleif/EntityRecord (dissoc (gleif/fetch-entity lei) :hq-address))) "not vacuous"))
    (with-redefs [intake/fetch-json (fetch-stub [])]
      (is (true? (valid gleif/CorporateTree (gleif/map-corporate-tree lei)))))))

;; --- linkedin ---

(deftest linkedin-shapes-match
  (let [company {:url "https://www.linkedin.com/company/acme/"
                 :title "Acme Corp: Overview | LinkedIn"
                 :text (str "IT Services and IT Consulting San Ramon, California 278K followers 5K-10K employees\n"
                            "Website\nhttps://acme.example\nFounded\n2006\nSpecialties\ncloud, data")
                 :meta {"og:description" "Acme builds things."}}
        profile {:url "https://www.linkedin.com/in/jane/"
                 :meta {"og:title" "Jane Doe - Engineer at Acme | LinkedIn"}
                 :text "Location: Berlin\n500+ connections"}]
    (is (true? (valid linkedin/Company (linkedin/parse-company-page company))))
    (is (true? (valid linkedin/Company (linkedin/parse-company-page {}))))
    (is (true? (valid linkedin/Company (linkedin/parse-company-page
                                         (assoc company :linkedin {:companyData {:employeeCount 5000 :founded 2006}})))))
    (is (true? (valid linkedin/Profile (linkedin/parse-profile-page profile))))
    (is (true? (valid linkedin/Profile (linkedin/parse-profile-page {}))))
    (is (true? (valid [:vector linkedin/Job] (linkedin/parse-jobs-page
                                               {:linkedin {:jobsData {:listings [{:title " Dev " :company "Acme"}]}}}))))
    (is (true? (valid [:vector linkedin/Job] (linkedin/parse-jobs-page {:url "https://www.linkedin.com/jobs/"}))))
    (is (not (m/validate linkedin/Company {:url nil})) "not vacuous")))

;; --- lobsters ---

(deftest lobsters-shapes-match
  (let [story {:title "T" :url "" :comments_url "https://lobste.rs/s/abc" :score 5
               :comment_count 2 :tags ["clojure"] :submitter_user "alice"}]
    (is (true? (valid lobsters/Story (#'lobsters/parse-story story))))
    (is (true? (valid lobsters/Story (#'lobsters/parse-story {}))))
    (is (true? (valid (schema/result lobsters/Story) [(#'lobsters/parse-story story)])))
    (is (not (m/validate lobsters/Story (assoc (#'lobsters/parse-story story) :tags "clojure"))) "not vacuous")))

;; --- mastodon ---

(deftest mastodon-shapes-match
  (with-redefs [codec/strip-tags #(clojure.string/replace % #"<[^>]+>" "")]
    (let [status {:content "<p>Hello<br/>world</p>" :url "https://fosstodon.org/@a/1"
                  :favourites_count 3 :reblogs_count nil :replies_count 1}
          link   {:title "L" :url "https://x.example" :description "d"
                  :history [{:day "1700000000" :uses "3" :accounts "2"}]}]
      (is (true? (valid mastodon/Status (#'mastodon/parse-status status))))
      (is (true? (valid mastodon/Status (#'mastodon/parse-status {}))))
      (is (true? (valid mastodon/Link (#'mastodon/parse-link link))))
      (is (true? (valid mastodon/Link (#'mastodon/parse-link (dissoc link :history)))))
      (is (not (m/validate mastodon/Status (assoc (#'mastodon/parse-status status) :score nil))) "not vacuous"))))

;; --- rss ---

(deftest rss-shapes-match
  (let [rss-xml "<rss version=\"2.0\"><channel><title>Blog</title>
                   <item><title>A</title><link>https://b.example/a</link>
                     <description>&lt;p&gt;Hi&lt;/p&gt;</description><pubDate>Mon, 01 Jan 2026 00:00:00 GMT</pubDate>
                     <category>news</category></item>
                   <item><title>B</title></item></channel></rss>"
        atom-xml "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Atom</title>
                    <entry><title>E</title><link rel=\"alternate\" href=\"https://b.example/e\"/>
                      <updated>2026-01-01T00:00:00Z</updated><author><name>Ann</name></author>
                      <category term=\"clj\"/></entry></feed>"]
    (is (true? (valid rss/Feed (#'rss/parse-xml-feed rss-xml))))
    (is (true? (valid rss/Feed (#'rss/parse-xml-feed atom-xml))))
    (is (true? (valid (schema/one rss/Feed) (#'rss/parse-xml-feed "<not xml"))))
    (is (= "https://b.example/e" (-> (#'rss/parse-xml-feed atom-xml) :items first :url)))
    (is (not (m/validate rss/Feed {:feed-title "x" :items [{:tags "clj"}]})) "not vacuous")))

;; --- sec-edgar ---

(deftest sec-edgar-shapes-match
  (let [fact {:val 100 :end "2025-12-31" :filed "2026-02-01" :fp "FY" :form "10-K"}]
    (with-redefs [intake/fetch-json
                  (fetch-stub [["companyfacts" {:entityName "Acme"
                                                :facts {:us-gaap {:Revenues {:units {:USD [fact]}}
                                                                  :Assets {:units {:USD [(assoc fact :fp "Q3")]}}}}}]
                               ["submissions" {:name "Acme" :sic "3571" :sicDescription "Computers"
                                               :stateOfIncorporation "DE" :fiscalYearEnd "1231"
                                               :filings {:recent {:form ["10-K" "8-K"]
                                                                  :filingDate ["2026-02-01" "2026-03-01"]
                                                                  :accessionNumber ["0000320193-26-000001" "0000320193-26-000002"]
                                                                  :primaryDocDescription ["10-K" nil]
                                                                  :primaryDocument ["a.htm" "b.htm"]}}}]
                               ["search-index" {:hits {:hits [{:_source {:ciks ["0000320193"] :display_names ["Acme (AC)"]
                                                                         :file_type "10-K" :file_date "2026-02-01"
                                                                         :form "10-K" :sics ["3571"]}}
                                                              {:_source {:ciks ["0000320193"]}}]}}]])]
      (is (true? (valid (schema/result sec/Company) (sec/search-companies "acme"))))
      (is (true? (valid (schema/one sec/CompanyFacts) (sec/fetch-company-facts 320193))))
      (is (true? (valid (schema/one sec/Filings) (sec/fetch-filings "320193" :filing-type "10-K"))))
      (is (not (m/validate sec/CompanyFacts (assoc (sec/fetch-company-facts 320193) :cik 320193))) "not vacuous"))
    ;; Insider trades read :hits as a flat vector.
    (with-redefs [intake/fetch-json
                  (fetch-stub [["search-index" {:hits [{:_source {:display_names ["Doe Jane"] :file_date "2026-01-02"
                                                                  :file_type "4" :entity_name "Acme" :entity_id "320193"}}]}]])]
      (is (true? (valid (schema/result sec/InsiderTrade) (sec/fetch-insider-trades "acme")))))))
