(ns dvergr.mail.inbox
  "Read the room's attached mailbox, if any. `dvergr.mail/*inbox*` is the
   fork-aware datahike conn (nil when no mailbox is attached); these are plain
   datahike queries over it — copy/extend like any intake. IMAP sync + sending
   are daemon-side, not exposed here."
  (:require [datahike.api :as d] [clojure.string :as str]))

(defn attached?
  "True when this room has a mailbox attached."
  []
  (some? dvergr.mail/*inbox*))

(defn recent
  "Most recent messages as {:uid :subject :from :date}. kwargs: :limit (default 20)."
  [& {:keys [limit] :or {limit 20}}]
  (when dvergr.mail/*inbox*
    (->> (d/q '[:find ?uid ?subj ?from ?date
                :where [?m :mail.message/uid ?uid]
                       [?m :mail.message/subject ?subj]
                       [?m :mail.message/from ?from]
                       [?m :mail.message/date ?date]]
              @dvergr.mail/*inbox*)
         (sort-by #(nth % 3))
         reverse
         (take limit)
         (mapv (fn [[uid s f dt]] {:uid uid :subject s :from f :date dt})))))

(defn search
  "Messages whose subject or from contains `q` (case-insensitive)."
  [q]
  (when dvergr.mail/*inbox*
    (let [ql (str/lower-case (str q))]
      (->> (d/q '[:find ?uid ?subj ?from
                  :where [?m :mail.message/uid ?uid]
                         [?m :mail.message/subject ?subj]
                         [?m :mail.message/from ?from]]
                @dvergr.mail/*inbox*)
           (filter (fn [[_ s f]] (or (str/includes? (str/lower-case (str s)) ql)
                                     (str/includes? (str/lower-case (str f)) ql))))
           (mapv (fn [[uid s f]] {:uid uid :subject s :from f}))))))

(defn unread
  "Messages without the :seen flag as {:uid :subject}."
  []
  (when dvergr.mail/*inbox*
    (->> (d/q '[:find ?uid ?subj
                :where [?m :mail.message/uid ?uid]
                       [?m :mail.message/subject ?subj]
                       (not [?m :mail.message/flags :seen])]
              @dvergr.mail/*inbox*)
         (mapv (fn [[uid s]] {:uid uid :subject s})))))
