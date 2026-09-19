(ns dvergr.intake.schema-test
  "Every public fn of the stdlib carries a malli function schema that
   compiles, and the shaping code produces data matching it (fixtures)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [dvergr.intake.hn :as hn]
            [dvergr.intake.schema :as schema]))

(defn- stdlib-namespaces []
  (->> (concat (fs/glob "dvergr/intake" "*.clj") (fs/glob "dvergr/mail" "*.clj"))
       (map #(-> (str %) (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-") symbol))
       sort))

(deftest every-public-fn-has-a-schema
  (doseq [ns-sym (stdlib-namespaces)]
    (require ns-sym)
    (doseq [[sym v] (ns-publics ns-sym)
            :when (fn? @v)
            :let [s (:malli/schema (meta v))]]
      (testing (str ns-sym "/" sym)
        (is (some? s) (str ns-sym "/" sym " carries no :malli/schema"))
        (when s
          (is (try (m/function-schema s) true
                   (catch Exception e (str "does not compile: " (ex-message e) " " (pr-str (ex-data e)))))))))))

(defn- valid [schema x]
  (or (m/validate schema x) (me/humanize (m/explain schema x))))

(deftest hn-shapes-match
  (let [hit {:title "T" :url nil :points 12 :num_comments 3 :objectID "42"}]
    (is (true? (valid hn/Story (#'hn/parse-story hit))))
    (is (true? (valid (schema/result hn/Story) [(#'hn/parse-story hit)])))
    (is (true? (valid (schema/result hn/Story) {:error "HTTP 500"})))
    (is (not (m/validate hn/Story {:title "T"})) "not vacuous")))
