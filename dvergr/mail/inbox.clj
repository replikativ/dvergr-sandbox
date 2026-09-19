(ns dvergr.mail.inbox
  "Read the room's attached mailbox, if any. `dvergr.mail/*inbox*` is the
   fork-aware datahike conn (nil when no mailbox is attached); these are plain
   datahike queries over it — copy/extend like any intake. IMAP sync + sending
   are daemon-side, not exposed here.

   Queries pull each message entity (keyed by :db/id), so every message is
   returned exactly once and a missing subject/from/date comes back as nil."
  (:require [datahike.api :as d] [clojure.string :as str]
            [dvergr.intake.schema :as schema]))

(def InboxMessage
  "One message as `recent` returns it (:uid is the IMAP UID, :date an instant);
   :subject/:from/:date are nil when the message lacks them."
  [:map [:uid :int] [:subject [:maybe :string]] [:from [:maybe :string]] [:date [:maybe 'inst?]]])

(def MessageSummary
  "One message as `search` returns it."
  [:map [:uid :int] [:subject [:maybe :string]] [:from [:maybe :string]]])

(def UnreadMessage
  "One unread message as `unread` returns it."
  [:map [:uid :int] [:subject [:maybe :string]]])

(defn- ->message
  "A pulled message entity as {:uid :subject :from :date}."
  [m]
  {:uid     (:mail.message/uid m)
   :subject (:mail.message/subject m)
   :from    (:mail.message/from m)
   :date    (:mail.message/date m)})

(defn- messages
  "Every message entity (optionally only unread ones), one map each. Pulling
   :db/id keeps entities with identical attributes distinct in the result set."
  [& {:keys [unread?]}]
  (->> (d/q (if unread?
              '[:find (pull ?m [:db/id :mail.message/uid :mail.message/subject
                                :mail.message/from :mail.message/date])
                :where [?m :mail.message/uid _]
                       (not [?m :mail.message/flags :seen])]
              '[:find (pull ?m [:db/id :mail.message/uid :mail.message/subject
                                :mail.message/from :mail.message/date])
                :where [?m :mail.message/uid _]])
            @dvergr.mail/*inbox*)
       (map (comp ->message first))))

(defn attached?
  "True when this room has a mailbox attached."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (some? dvergr.mail/*inbox*))

(defn recent
  "Most recent messages as {:uid :subject :from :date}. kwargs: :limit (default 20)."
  {:malli/schema [:=> [:cat (schema/kwargs :limit :int)] [:maybe [:vector InboxMessage]]]}
  [& {:keys [limit] :or {limit 20}}]
  (when dvergr.mail/*inbox*
    (->> (messages)
         (sort-by :date)
         reverse
         (take limit)
         vec)))

(defn search
  "Messages whose subject or from contains `q` (case-insensitive)."
  {:malli/schema [:=> [:cat :any] [:maybe [:vector MessageSummary]]]}
  [q]
  (when dvergr.mail/*inbox*
    (let [ql (str/lower-case (str q))]
      (->> (messages)
           (filter (fn [{:keys [subject from]}]
                     (or (str/includes? (str/lower-case (str subject)) ql)
                         (str/includes? (str/lower-case (str from)) ql))))
           (mapv #(select-keys % [:uid :subject :from]))))))

(defn unread
  "Messages without the :seen flag as {:uid :subject}."
  {:malli/schema [:=> [:cat] [:maybe [:vector UnreadMessage]]]}
  []
  (when dvergr.mail/*inbox*
    (->> (messages :unread? true)
         (mapv #(select-keys % [:uid :subject])))))
