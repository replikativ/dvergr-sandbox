(ns dvergr.intake.lobsters
  "Lobste.rs via JSON API (no auth). GET JSON, reshape the stories. Copy this
   to build your own tag-filtered feed intake."
  (:require [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [clojure.string :as str]))

(def ^:private base-url "https://lobste.rs")

(def Story
  "One Lobste.rs story as `fetch-hottest` returns it."
  [:map [:title [:maybe :string]] [:url [:maybe schema/Url]] [:score [:maybe :int]]
   [:comments [:maybe :int]] [:tags [:maybe [:vector :string]]] [:source [:= :lobsters]]
   [:submitter [:maybe :string]]])

(defn- parse-story [story]
  {:title     (:title story)
   :url       (let [u (:url story)]              ; text posts have :url ""
                (if (str/blank? u) (:comments_url story) u))
   :score     (:score story)
   :comments  (:comment_count story)
   :tags      (:tags story)
   :source    :lobsters
   :submitter (:submitter_user story)})

(defn fetch-hottest
  "Fetch hottest stories, optionally filtered by tag. kwargs: :tag :count.
   Vector of maps or {:error}."
  {:malli/schema [:=> [:cat (schema/kwargs :tag :string :count :int)] (schema/result Story)]}
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
