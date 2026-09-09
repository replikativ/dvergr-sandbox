(ns dvergr.intake.evidence-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.intake.evidence :as evidence]))

(def response
  {:url "https://example.org/source" :status 200 :body "Alpha supports shared work."
   :dvergr/acquisition {:id (random-uuid) :capture :captured :body-ref (random-uuid)}
   :dvergr/fixture-id (random-uuid)})

(deftest spans-preserve-receipts-without-retaining-whole-bodies
  (let [selected (evidence/quote-span response :body 6 26)]
    (is (= "supports shared work" (:quote selected)))
    (is (= {:field :body :start 6 :end 26 :unit :utf-16} (:selection selected)))
    (is (= (select-keys response [:url :dvergr/acquisition :dvergr/fixture-id])
           (dissoc selected :quote :selection)))
    (is (not (contains? selected :body)))
    (is (= selected (evidence/quote-span response :body 6 26)))))

(deftest extracted-text-is-explicitly-not-the-original-body
  (let [r (assoc response :body "<p>Alpha</p>" :text "Alpha")
        selected (evidence/quote-span r :text 0 5)]
    (is (= "Alpha" (:quote selected)))
    (is (= :text (get-in selected [:selection :field])))
    (is (= (:dvergr/acquisition r) (:dvergr/acquisition selected)))))

(deftest no-fabricated-receipts-or-silent-span-correction
  (doseq [r [nil {} (dissoc response :dvergr/acquisition)
             (assoc-in response [:dvergr/acquisition :id] "not-a-uuid")
             (assoc response :error "failed") (assoc response :status 404)
             (assoc response :status nil) (assoc response :body {:not "text"})]]
    (is (thrown? clojure.lang.ExceptionInfo (evidence/quote-span r :body 0 5))))
  (doseq [[field start end] [[:body -1 5] [:body 2 2] [:body 5 2]
                            [:body 0 100] [:body 0.5 5] [:body 0 nil]
                            [:body nil 5] [:body 0 999999999999999999999N]
                            [:missing 0 1] [:url 0 1]]]
    (is (thrown? clojure.lang.ExceptionInfo (evidence/quote-span response field start end)))))

(deftest capture-disabled-is-not-misrepresented-as-archived
  (let [r (-> response (dissoc :status :dvergr/fixture-id)
              (assoc :dvergr/acquisition {:id (random-uuid) :capture :disabled}))
        selected (evidence/quote-span r :body 0 5)]
    (is (= (:dvergr/acquisition r) (:dvergr/acquisition selected)))
    (is (not (contains? selected :dvergr/fixture-id)))
    (is (nil? (get-in selected [:dvergr/acquisition :body-ref])))))

(deftest offsets-follow-clojure-string-indices
  (let [r (assoc response :text "A😀B")]
    (is (= "😀" (:quote (evidence/quote-span r :text 1 3))))))
