(ns dvergr.intake.devto
  "Dev.to via the Forem API (no auth). GET JSON, reshape the articles."
  (:require [dvergr.intake.core :as intake]
            [clojure.string :as str]))

(def ^:private api-base "https://dev.to/api")

(defn- parse-article [article]
  {:title    (:title article)
   :url      (:url article)
   :score    (:public_reactions_count article)
   :comments (:comments_count article)
   :tags     (some-> (:tag_list article)
                     (as-> tl (if (string? tl)
                                (str/split tl #",\s*")
                                tl)))
   :source   :devto
   :summary  (:description article)})

(defn fetch-top
  "Fetch top Dev.to articles. kwargs: :tag :time-range (top N days, default 1)
   :count (max 30). Vector of maps or {:error}."
  [& {:keys [tag time-range count]
      :or {count 20 time-range 1}}]
  (let [params (cond-> {:per_page (min count 30)
                        :top time-range}
                 tag (assoc :tag tag))
        data   (intake/fetch-json (str api-base "/articles")
                                  :query-params params)]
    (if (:error data)
      data
      (mapv parse-article data))))
