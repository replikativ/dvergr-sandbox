(ns dvergr.intake.lobsters
  "Lobste.rs via JSON API (no auth). GET JSON, reshape the stories. Copy this
   to build your own tag-filtered feed intake."
  (:require [dvergr.intake.core :as intake]))

(def ^:private base-url "https://lobste.rs")

(defn- parse-story [story]
  {:title     (:title story)
   :url       (or (:url story) (:comments_url story))
   :score     (:score story)
   :comments  (:comment_count story)
   :tags      (:tags story)
   :source    :lobsters
   :submitter (:submitter_user story)})

(defn fetch-hottest
  "Fetch hottest stories, optionally filtered by tag. kwargs: :tag :count.
   Vector of maps or {:error}."
  [& {:keys [tag count] :or {count 20}}]
  (let [url  (if tag
               (str base-url "/t/" tag ".json")
               (str base-url "/hottest.json"))
        data (intake/fetch-json url)]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv parse-story)))))
