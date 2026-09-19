(ns dvergr.intake.hn
  "Hacker News via the Algolia API (no auth). The simplest intake: GET JSON,
   reshape the hits. Copy this to build your own."
  (:require [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]))

(def ^:private algolia-base "https://hn.algolia.com/api/v1")

(def Story
  "One Hacker News story as the fns below return it."
  [:map [:title [:maybe :string]] [:url schema/Url] [:score [:maybe :int]]
   [:comments [:maybe :int]] [:source [:= :hn]] [:hn-id :string]])

(defn- parse-story [hit]
  {:title    (:title hit)
   :url      (or (:url hit) (str "https://news.ycombinator.com/item?id=" (:objectID hit)))
   :score    (:points hit)
   :comments (:num_comments hit)
   :source   :hn
   :hn-id    (:objectID hit)})

(defn fetch-top
  "Fetch HN front-page stories. kwargs: :count :min-points :query."
  {:malli/schema [:=> [:cat (schema/kwargs :count :int :min-points :int :query :string)] (schema/result Story)]}
  [& {:keys [count min-points query]
      :or {count 20}}]
  (let [params (cond-> {:tags "front_page" :hitsPerPage count}
                 query (assoc :query query))
        data   (intake/fetch-json (str algolia-base "/search") :query-params params)]
    (if (:error data)
      data
      (->> (:hits data)
           (map parse-story)
           (filter #(or (nil? min-points) (>= (or (:score %) 0) min-points)))
           vec))))

(defn search-stories
  "Search HN stories by keyword. kwargs: :count :days-back :min-points."
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int :days-back :int :min-points :int)] (schema/result Story)]}
  [query & {:keys [count days-back min-points]
            :or {count 20 days-back 7}}]
  (let [params (cond-> {:query query
                        :tags "story"
                        :hitsPerPage count
                        :numericFilters (str "created_at_i>" (intake/days-ago-epoch days-back))}
                 min-points (update :numericFilters #(str % ",points>" min-points)))
        data   (intake/fetch-json (str algolia-base "/search_by_date") :query-params params)]
    (if (:error data)
      data
      (->> (:hits data) (map parse-story) vec))))
