(ns dvergr.intake.provenance-test
  (:require [clojure.test :refer [deftest is]]
            [env]
            [babashka.http-client :as http]
            [dvergr.intake.web-search :as search]
            [dvergr.intake.web-fetch :as fetch]))

(def markers {:dvergr/acquisition {:id (random-uuid) :capture :captured :body-ref (random-uuid)}
              :dvergr/fixture-id (random-uuid)})

(deftest search-preserves-provenance-on-success-and-errors
  (doseq [[status body expected]
          [[200 "{\"web\":{\"results\":[{\"title\":\"Aster\",\"url\":\"https://example.org/a\"}]}}" :results]
           [503 "unavailable" :error]
           [200 "not json" :error]]]
    (with-redefs [http/get (fn [_ opts]
                             (is (false? (:throw opts)))
                             (merge markers {:status status :body body}))]
      (let [result (search/search "agent teams")]
        (is (= markers (select-keys result (keys markers))))
        (is (contains? result expected))
        (is (= "agent teams" (:query result)))))))

(deftest fetch-preserves-provenance-even-when-text-is-truncated
  (with-redefs [http/get (fn [& _] (merge markers {:status 200 :headers {} :body "complete source"}))]
    (let [result (fetch/fetch-page "https://example.org/a" :max-chars 4)]
      (is (= markers (select-keys result (keys markers))))
      (is (= "comp\n\n[content truncated]" (:text result))))))

(deftest fetch-errors-retain-markers-but-not-success-text
  (doseq [response [{:status 404 :body "not found"}
                    {:status 200 :headers {"content-type" "text/html"} :body "<p>fails in test codec</p>"}]]
    (with-redefs [http/get (fn [& _] (merge markers response))]
      (let [result (fetch/fetch-page "https://example.org/a")]
        (is (= markers (select-keys result (keys markers))))
        (is (string? (:error result)))
        (is (not (contains? result :text)))))))

(deftest unrecorded-responses-do-not-invent-provenance
  (with-redefs [http/get (fn [& _] {:status 200 :body "{\"web\":{\"results\":[]}}"})]
    (is (= {:query "nothing" :results []} (search/search "nothing"))))
  (with-redefs [http/get (fn [& _] (throw (ex-info "offline" {})))]
    (is (= {:query "nothing" :error "offline"} (search/search "nothing")))
    (is (= {:url "https://example.org/a" :error "offline"} (fetch/fetch-page "https://example.org/a")))))
