(ns dvergr.intake.mastodon
  "Mastodon via the public trends API (no auth). GET JSON, strip post HTML
   into plain text. Shows light HTML munging via the `html` primitive +
   str/replace, no host SAX/HTML libs."
  (:require [dvergr.codec :as codec] [dvergr.intake.core :as intake]
            [clojure.string :as str]))

(def ^:private default-instance "fosstodon.org")

(defn- strip-html [s]
  (when s
    (-> s
        (str/replace #"<br\s*/?>" "\n")
        (codec/strip-tags)
        (codec/decode-entities)
        str/trim)))

(defn- parse-status [status]
  {:title    (let [text (strip-html (:content status))]
               (if (> (count text) 120)
                 (str (subs text 0 120) "...")
                 text))
   :url      (:url status)
   :score    (+ (or (:favourites_count status) 0)
                (or (:reblogs_count status) 0))
   :comments (:replies_count status)
   :source   :mastodon
   :summary  (strip-html (:content status))})

(defn- parse-link [link]
  {:title   (:title link)
   :url     (:url link)
   :score   (or (:history link) 0)
   :source  :mastodon
   :summary (:description link)})

(defn fetch-trending
  "Fetch trending statuses or links from a Mastodon instance.
   kwargs: :instance (default fosstodon.org) :type (\"statuses\"|\"links\")
   :count (max 40). Vector of maps or {:error}."
  [& {:keys [instance type count]
      :or {instance default-instance type "statuses" count 20}}]
  (let [url  (str "https://" instance "/api/v1/trends/" type)
        data (intake/fetch-json url
                                :query-params {:limit (min count 40)})]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv (if (= type "links") parse-link parse-status))))))
