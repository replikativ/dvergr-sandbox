(ns dvergr.intake.core
  "Shared substrate for sandbox intakes — HTTP+JSON fetch, date helpers, light
   formatting. Built over the established libs the model already knows:
   `babashka.http-client` (response `{:status :headers :body}`, body is a raw
   string — you parse it) and `cheshire.core` (JSON). A new intake is just a fn
   that fetches + shapes; copy this pattern. See dvergr/intake/hn.clj."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(def user-agent "dvergr/1.0 (intake)")

(defn fetch-json
  "GET `url`, return parsed JSON (keyword keys) or {:error \"…\"}.
   kwargs: :headers :query-params :timeout (ms, default 15000)."
  [url & {:keys [headers query-params timeout] :or {timeout 15000}}]
  (try
    (let [resp (http/get url (cond-> {:timeout timeout
                                      :headers (merge {"User-Agent" user-agent
                                                       "Accept" "application/json"} headers)}
                               query-params (assoc :query-params query-params)))]
      (if (= 200 (:status resp))
        (json/parse-string (:body resp) true)
        {:error (str "HTTP " (:status resp))}))
    (catch Throwable e
      {:error (or (some->> (:status (ex-data e)) (str "HTTP ")) (.getMessage e))})))

(defn fetch-text
  "GET `url`, return the raw body string or {:error \"…\"}.
   kwargs: :headers :query-params :timeout (ms, default 15000)."
  [url & {:keys [headers query-params timeout] :or {timeout 15000}}]
  (try
    (let [resp (http/get url (cond-> {:timeout timeout
                                      :headers (merge {"User-Agent" user-agent} headers)}
                               query-params (assoc :query-params query-params)))]
      (if (= 200 (:status resp))
        (:body resp)
        {:error (str "HTTP " (:status resp))}))
    (catch Throwable e
      {:error (or (some->> (:status (ex-data e)) (str "HTTP ")) (.getMessage e))})))

;; date helpers (java.time is allowlisted in the sandbox)
(defn days-ago-iso
  "ISO date (yyyy-MM-dd) `n` days before today (UTC)."
  [n]
  (.format (.minusDays (java.time.LocalDate/now java.time.ZoneOffset/UTC) (long n))
           java.time.format.DateTimeFormatter/ISO_LOCAL_DATE))

(defn days-ago-epoch
  "Epoch seconds `n` days ago."
  [n]
  (.getEpochSecond (.minusSeconds (java.time.Instant/now) (* (long n) 86400))))

(defn format-items
  "Render a vector of {:title :url :score :comments} maps as a markdown list."
  [header items]
  (apply str header "\n"
         (for [{:keys [title url score comments]} items]
           (str "• " title
                (when url (str " — " url))
                (when score (str " (" score (when comments (str " pts, " comments " comments")) ")"))
                "\n"))))
