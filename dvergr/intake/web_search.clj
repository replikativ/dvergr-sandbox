(ns dvergr.intake.web-search
  "Web search via the Brave Search API. GET JSON, reshape the web results.
   Built over the sandbox `http`/`json`/`env` primitives — no host libs.

   Configuration: BRAVE_API_KEY (or BRAVE_TOKEN) environment variable."
  (:require [babashka.http-client :as http] [cheshire.core :as json] [dvergr.intake.core :as intake]
            [clojure.string :as str]))

(def ^:private brave-api-url
  "https://api.search.brave.com/res/v1/web/search")

(def ^:private max-count 50)

(defn- get-brave-api-key []
  (let [api-key (env/get "BRAVE_API_KEY")
        token   (env/get "BRAVE_TOKEN")]
    (cond
      (not (str/blank? api-key)) api-key
      (not (str/blank? token))   token
      :else
      (throw (ex-info "BRAVE_API_KEY (or BRAVE_TOKEN) environment variable not set"
                      {:error :missing-api-key})))))

(defn- host
  "Extract the host from a URL string (replaces java.net.URI/.getHost)."
  [url]
  (when url
    (second (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)" url))))

(defn- parse-results
  "Pull the relevant fields out of Brave's web result objects."
  [body]
  (let [web (get-in body [:web :results] [])]
    (mapv (fn [r]
            {:title       (:title r)
             :url         (:url r)
             :description (:description r)
             :age         (:age r)
             :site-name   (host (:url r))})
          web)))

(defn search
  "Search the web via Brave. Returns a map:

     {:query Q :results [{:title :url :description :age :site-name} ...]}  ;; success
     {:query Q :error  ERR}                                               ;; failure

   Options:
     :count      Number of results (1–50, default 5)
     :freshness  pd (24h), pw (week), pm (month), py (year)
     :country    2-letter country code (default \"US\")"
  [query & {:keys [count freshness country]
            :or {count 5 country "US"}}]
  (try
    (let [api-key (get-brave-api-key)
          params  (cond-> {:q     query
                           :count (min count max-count)}
                    freshness (assoc :freshness freshness)
                    country   (assoc :country country))
          resp    (http/get brave-api-url
                            {:query-params params
                             :headers      {"Accept"               "application/json"
                                            "X-Subscription-Token" api-key}
                             :timeout      30000})
          body    (:body resp)
          body    (if (string? body) (json/decode body true) body)]
      {:query query :results (parse-results body)})
    (catch Throwable e
      {:query query
       :error (or (some->> (:status (ex-data e)) (str "Brave API error: HTTP "))
                  (.getMessage e))})))
