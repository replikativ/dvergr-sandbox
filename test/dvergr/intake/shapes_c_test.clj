(ns dvergr.intake.shapes-c-test
  "Fixture checks: the pure shaping fns produce data matching their schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [datahike.api]
            [dvergr.codec :as codec]
            [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [dvergr.intake.twitter :as twitter]
            [dvergr.intake.wayback :as wayback]
            [dvergr.intake.web-fetch :as web-fetch]
            [dvergr.intake.web-search :as web-search]
            [dvergr.intake.wikidata :as wikidata]
            [dvergr.intake.youtube :as youtube]
            [dvergr.intake.zulip :as zulip]
            [dvergr.mail]
            [dvergr.mail.inbox :as inbox]))

(defn- valid [schema x]
  (or (m/validate schema x) (me/humanize (m/explain schema x))))

(def ^:private markers
  {:dvergr/acquisition {:id (random-uuid) :capture :captured} :dvergr/fixture-id (random-uuid)})

(deftest twitter-shapes-match
  (is (= "1234567890123" (twitter/extract-tweet-id "https://x.com/alice/status/1234567890123")))
  (with-redefs [intake/fetch-json
                (fn [& _] {:code 200 :message "OK"
                           :tweet {:url "https://x.com/alice/status/1234567890123"
                                   :text "see https://example.org/post"
                                   :created_at "Wed Sep 17 10:00:00 +0000 2026"
                                   :author {:name "Alice" :screen_name "alice"}
                                   :entities {:urls [{:expanded_url "https://example.org/post"}
                                                     {:expanded_url "https://t.co/AbC123"}
                                                     {:expanded_url "https://chat.com/share/1"}
                                                     {:expanded_url "https://reddit.com/r/x/t.co?x=1"}]}}})]
    (let [t (twitter/lookup-tweet "https://x.com/alice/status/1234567890123")]
      (is (true? (valid twitter/Tweet t)))
      (is (= ["https://example.org/post" "https://chat.com/share/1" "https://reddit.com/r/x/t.co?x=1"]
             (:links t))
          "only host-exactly-t.co links are dropped")))
  (with-redefs [intake/fetch-json (fn [& _] {:code 404 :message "NOT_FOUND"})]
    (is (true? (valid (schema/one twitter/Tweet) (twitter/lookup-tweet "1234567890123")))))
  (is (true? (valid (schema/one twitter/Tweet) (twitter/lookup-tweet "not a tweet"))))
  (is (not (m/validate twitter/Tweet {:tweet-id "1" :url "u" :links nil})) "not vacuous"))

(def ^:private cdx-body
  (json/encode [["urlkey" "timestamp" "original" "mimetype" "statuscode" "digest" "length"]
                ["org,example)/" "20200101000000" "https://example.org/" "text/html" "200" "ABC" "1234"]
                ["org,example)/" "20210101000000" "https://example.org/" "text/html" "200" "DEF" "1300"]]))

(deftest wayback-shapes-match
  (with-redefs [http/get (fn [& _] {:status 200 :body cdx-body})]
    (let [rows (wayback/search-snapshots "example.org")]
      (is (true? (valid (schema/result wayback/CdxSnapshot) rows)))
      (is (= #{:urlkey :timestamp :original :mimetype :statuscode :digest :length}
             (set (keys (first rows))))
          "raw CDX keys, as the docstring says"))
    (let [versions (wayback/track-changes "example.org")]
      (is (= 2 (count versions)))
      (is (true? (valid (schema/result wayback/Version) versions)))))
  (with-redefs [http/get (fn [& _] {:status 200 :body "[]"})]
    (is (= [] (wayback/search-snapshots "example.org"))))
  (with-redefs [http/get (fn [& _] (throw (ex-info "boom" {:status 503})))]
    (is (true? (valid (schema/result wayback/CdxSnapshot) (wayback/search-snapshots "example.org"))))
    (is (true? (valid (schema/one wayback/Snapshot) (wayback/fetch-snapshot "example.org" "20200101000000")))))
  (with-redefs [intake/fetch-json
                (fn [& _] {:archived_snapshots
                           {:closest {:available true :status "200" :timestamp "20200101000000"
                                      :url "http://web.archive.org/web/20200101000000/https://example.org/"}}})]
    (is (true? (valid (schema/one wayback/Availability) (wayback/check-availability "example.org")))))
  (with-redefs [intake/fetch-json (fn [& _] {:archived_snapshots {}})]
    (is (true? (valid wayback/Availability (wayback/check-availability "example.org")))))
  (with-redefs [http/get (fn [& _] {:status 200 :body "<html><title> Home </title><p>Hi &amp; bye</p></html>"})
                codec/strip-tags (fn [s] (clojure.string/replace s #"<[^>]+>" " "))]
    (let [snap (wayback/fetch-snapshot "example.org" "20200101000000")]
      (is (= "Home" (:title snap)))
      (is (true? (valid wayback/Snapshot snap)))))
  (is (not (m/validate wayback/Availability {:available true :url "u" :timestamp 2020})) "not vacuous"))

(deftest web-fetch-shapes-match
  (with-redefs [http/get (fn [& _] (merge markers {:status 200 :headers {"content-type" "text/plain"} :body "plain text"}))]
    (is (true? (valid web-fetch/Page (web-fetch/fetch-page "https://example.org/a")))))
  (with-redefs [http/get (fn [& _] (merge markers {:status 200 :headers {"content-type" "text/html"}
                                                   :body "<html><title>T</title><p>x</p></html>"}))
                codec/strip-tags (fn [s] (clojure.string/replace s #"<[^>]+>" " "))]
    (let [page (web-fetch/fetch-page "https://example.org/a")]
      (is (= "T" (:title page)))
      (is (true? (valid web-fetch/Page page)))))
  (with-redefs [http/get (fn [& _] (merge markers {:status 404 :body "nope"}))]
    (is (true? (valid web-fetch/Page (web-fetch/fetch-page "https://example.org/a")))))
  (with-redefs [http/get (fn [& _] (throw (ex-info "offline" {})))]
    (is (true? (valid web-fetch/Page (web-fetch/fetch-page "https://example.org/a")))))
  (with-redefs [codec/strip-tags (fn [s] (clojure.string/replace s #"<[^>]+>" " "))]
    (is (= "a b" (#'web-fetch/strip-html "  <p>a</p> \n b "))))
  (is (not (m/validate web-fetch/Page {:url "https://example.org/a" :text 42})) "not vacuous"))

(deftest web-search-shapes-match
  (let [body {:web {:results [{:title "Aster" :url "https://example.org/a" :description "d" :age "2 days ago"}
                              {:title "No url"}]}}
        rs   (#'web-search/parse-results body)]
    (is (= "example.org" (:site-name (first rs))))
    (is (true? (valid [:vector web-search/SearchResult] rs))))
  (is (= [] (#'web-search/parse-results {})))
  (with-redefs [http/get (fn [& _] (merge markers {:status 200 :body (json/encode {:web {:results [{:title "Aster" :url "https://example.org/a"}]}})}))]
    (is (true? (valid web-search/SearchResponse (web-search/search "agent teams")))))
  (with-redefs [http/get (fn [& _] {:status 503 :body "unavailable"})]
    (is (true? (valid web-search/SearchResponse (web-search/search "agent teams")))))
  (is (not (m/validate web-search/SearchResponse {:query "q" :results [{:title 1}]})) "not vacuous"))

(defn- sparql [& rows]
  {:results {:bindings (vec (for [row rows]
                              (into {} (for [[k v] row] [k {:type "literal" :value v}]))))}})

(deftest wikidata-shapes-match
  (with-redefs [intake/fetch-json
                (fn [& _] {:search [{:id "Q312" :label "Apple Inc." :description "American technology company"
                                     :concepturi "http://www.wikidata.org/entity/Q312"}
                                    {:id "Q89" :label "apple" :concepturi "http://www.wikidata.org/entity/Q89"}]})]
    (is (true? (valid (schema/result wikidata/Entity) (wikidata/search-entities "apple")))))
  (with-redefs [intake/fetch-json (fn [& _] (sparql {:propLabel "industry" :valueLabel "consumer electronics"
                                                     :value "http://www.wikidata.org/entity/Q581105"}))]
    (is (true? (valid (schema/result wikidata/Fact) (wikidata/fetch-company-profile "Q312")))))
  (with-redefs [intake/fetch-json (fn [& _] (sparql {:subsidiary "http://www.wikidata.org/entity/Q270285"
                                                     :subsidiaryLabel "Beats Electronics"}))]
    (let [[s :as subs] (wikidata/fetch-subsidiaries "Q312")]
      (is (= "Q270285" (:id s)))
      (is (true? (valid (schema/result wikidata/Subsidiary) subs)))))
  (with-redefs [intake/fetch-json (fn [& _] (sparql {:company "http://www.wikidata.org/entity/Q2283"
                                                     :companyLabel "Microsoft" :industryLabel "software industry"}))]
    (is (true? (valid (schema/result wikidata/Competitor) (wikidata/fetch-competitors "Q312")))))
  (with-redefs [intake/fetch-json (fn [& _] (sparql {:company "http://www.wikidata.org/entity/Q2283"
                                                     :companyLabel "Microsoft" :employeesLabel "221000"}))]
    (is (true? (valid (schema/result wikidata/IndustryCompany) (wikidata/fetch-industry-companies "Q7397")))))
  (with-redefs [intake/fetch-json (fn [& _] (sparql {:item "http://www.wikidata.org/entity/Q1" :itemLabel "Universe"}))]
    (is (true? (valid (schema/result wikidata/Binding) (wikidata/custom-sparql "SELECT ...")))))
  (with-redefs [intake/fetch-json (fn [& _] {:error "HTTP 429"})]
    (is (true? (valid (schema/result wikidata/Fact) (wikidata/fetch-company-profile "Q312")))))
  (is (not (m/validate wikidata/Subsidiary {:id nil :name "x" :country nil})) "not vacuous"))

(deftest youtube-shapes-match
  (is (= "dQw4w9WgXcQ" (youtube/extract-video-id "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1")))
  (is (nil? (youtube/extract-video-id "nope")))
  (is (true? (valid [:enum :ok :bot-blocked :age-restricted :error]
                    (#'youtube/extract-playability {:playabilityStatus {:status "LOGIN_REQUIRED"
                                                                        :reason "Sign in to confirm you're not a bot"}}))))
  (is (= "Unknown title" (#'youtube/extract-title {})))
  (is (= "Hello & welcome it's" (#'youtube/parse-transcript-xml
                                 "<timedtext><body><p t=\"0\" d=\"1\">Hello &amp; <s>welcome</s></p><p t=\"1\" d=\"1\">it&#39;s</p></body></timedtext>")))
  (with-redefs [youtube/get-text (fn [url & _]
                                   (if (clojure.string/includes? url "watch")
                                     "\"INNERTUBE_API_KEY\": \"KEY_1\""
                                     "<transcript><text start=\"0\" dur=\"1\">Hi there</text></transcript>"))
                youtube/post-json (fn [& _] {:playabilityStatus {:status "OK"}
                                             :videoDetails {:title "A talk"}
                                             :captions {:playerCaptionsTracklistRenderer
                                                        {:captionTracks [{:baseUrl "https://example.org/tt" :languageCode "en"}]}}})
                spit (fn [& _] nil)]
    (let [t (youtube/get-transcript "dQw4w9WgXcQ")]
      (is (= "Hi there" (:transcript t)))
      (is (true? (valid youtube/Transcript t)))))
  (with-redefs [youtube/get-text (constantly nil)
                youtube/post-json (fn [& _] {:playabilityStatus {:status "OK"} :videoDetails {:title "A talk"}})]
    (is (true? (valid (schema/one youtube/Transcript) (youtube/get-transcript "dQw4w9WgXcQ")))))
  (is (not (m/validate youtube/Transcript {:video-id "x" :title "t" :language "en"})) "not vacuous"))

(deftest zulip-shapes-match
  (let [msg {:id 101 :sender_full_name "Alice" :display_recipient "clojure" :subject "help"
             :content "How do I …?" :timestamp 1758000000}]
    (is (true? (valid zulip/Message (#'zulip/parse-message msg))))
    (is (true? (valid zulip/Message (#'zulip/parse-message
                                     (assoc msg :display_recipient [{:id 1 :email "a@x" :full_name "A"}])))))
    (with-redefs [intake/fetch-json (fn [& _] {:result "success" :messages [msg]})]
      (is (true? (valid (schema/result zulip/Message) (zulip/fetch-messages "clojure" :topic "help"))))
      (is (true? (valid (schema/result zulip/Message) (zulip/search-messages "help"))))))
  (with-redefs [intake/fetch-json (fn [& _] {:streams [{:name "clojure" :stream_id 7 :description "Clojure"
                                                        :invite_only false}
                                                       {:name "secret" :stream_id 8 :description "" :invite_only true}]})]
    (let [streams (zulip/fetch-streams)]
      (is (= 1 (count streams)))
      (is (true? (valid (schema/result zulip/Stream) streams)))))
  (with-redefs [intake/fetch-json (fn [& _] {:topics [{:name "help" :max_id 101}]})]
    (is (true? (valid (schema/result zulip/Topic) (zulip/fetch-topics 7)))))
  (is (not (m/validate zulip/Message {:id "101" :sender "A" :stream "s" :topic "t" :content "c"
                                      :timestamp 1 :url "u"})) "not vacuous"))

(deftest inbox-shapes-match
  (testing "no mailbox attached: nil, and datahike is never queried"
    (binding [dvergr.mail/*inbox* nil]
      (is (false? (inbox/attached?)))
      (is (nil? (inbox/recent)))
      (is (nil? (inbox/search "x")))
      (is (nil? (inbox/unread)))))
  (testing "shapes built from pulled entities (datahike.api/q redefined)"
    (let [d1 #inst "2026-09-01T10:00:00Z" d2 #inst "2026-09-02T10:00:00Z"
          msgs [{:db/id 10 :mail.message/uid 1 :mail.message/subject "Hello"
                 :mail.message/from "Alice <a@x>" :mail.message/date d1}
                {:db/id 11 :mail.message/uid 2 :mail.message/subject "Invoice"
                 :mail.message/from "Bob <b@x>" :mail.message/date d2 :mail.message/flags #{:seen}}
                ;; same attributes as uid 2 but a distinct entity: must not collapse
                {:db/id 12 :mail.message/uid 2 :mail.message/subject "Invoice"
                 :mail.message/from "Bob <b@x>" :mail.message/date d2}
                ;; no subject / from / date: must still be returned
                {:db/id 13 :mail.message/uid 3}]
          ;; Evaluate the query just enough: a pull find over every entity with a
          ;; uid, dropping :seen ones when the query has a `not` clause.
          q-stub (fn [query _]
                   (let [[pull-expr] (rest (take-while #(not= :where %) query))
                         pattern (last pull-expr)
                         unread? (some #(and (seq? %) (= 'not (first %))) query)]
                     (assert (and (seq? pull-expr) (= 'pull (first pull-expr))) "query pulls entities")
                     (set (for [m msgs
                                :when (not (and unread? (contains? (:mail.message/flags m) :seen)))]
                            [(select-keys m pattern)]))))]
      (binding [dvergr.mail/*inbox* (atom :db)]
        (with-redefs [datahike.api/q q-stub]
          (is (true? (inbox/attached?)))
          (let [rs (inbox/recent)]
            (is (= [2 2 1 3] (map :uid rs)) "every message once, newest first, undated last")
            (is (= {:uid 3 :subject nil :from nil :date nil} (last rs)))
            (is (true? (valid [:vector inbox/InboxMessage] rs))))
          (is (= 2 (count (inbox/recent :limit 2))))
          (is (true? (valid [:vector inbox/MessageSummary] (inbox/search "alice"))))
          (is (= [1] (map :uid (inbox/search "alice"))))
          (is (= [2 2] (map :uid (inbox/search "INVOICE"))))
          (let [un (inbox/unread)]
            (is (= #{2 3 1} (set (map :uid un))))
            (is (= 3 (count un)))
            (is (true? (valid [:vector inbox/UnreadMessage] un))))))))
  (is (not (m/validate inbox/InboxMessage {:uid 1 :subject "s" :from "f" :date "2026-09-01"})) "not vacuous"))
