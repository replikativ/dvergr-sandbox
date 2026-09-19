(ns dvergr.intake.bluesky
  "Bluesky intake via AT Protocol (requires BLUESKY_HANDLE + BLUESKY_APP_PASSWORD).

   Keeps a cached session atom that auto-refreshes on 401. Built over the
   sandbox `http`/`json`/`env` primitives + `dvergr.intake.core` — `http/post` with a
   `:json` body posts JSON and the response body is auto-parsed. No host libs."
  (:require [babashka.http-client :as http] [cheshire.core :as json] [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [clojure.string :as str]))

(def ^:private pds-base "https://bsky.social")

(def Post
  "One Bluesky post; :title is the (truncated) text, :score likes + reposts."
  [:map [:title :string] [:url [:maybe schema/Url]] [:score :int]
   [:comments [:maybe :int]] [:source [:= :bluesky]]])

;; Session management — cached, auto-refreshes on 401
(def ^:private session (atom nil))

(defn- get-credentials []
  (let [handle (env/get "BLUESKY_HANDLE")
        password (env/get "BLUESKY_APP_PASSWORD")]
    (when (and (not (str/blank? handle))
               (not (str/blank? password)))
      {:handle handle :password password})))

(defn- create-session!
  "Authenticate and store session token."
  []
  (if-let [{:keys [handle password]} (get-credentials)]
    (try
      (let [response (http/post (str pds-base "/xrpc/com.atproto.server.createSession")
                                {:json {:identifier handle :password password}
                                 :timeout 30000})
            b    (:body response)
            data (if (string? b) (json/decode b true) b)]
        (reset! session {:access-jwt (:accessJwt data)
                         :did (:did data)})
        @session)
      (catch Throwable e
        {:error (str "Bluesky auth error: "
                     (or (some->> (:status (ex-data e)) (str "HTTP "))
                         (.getMessage e)))}))
    {:error "Set BLUESKY_HANDLE and BLUESKY_APP_PASSWORD env vars for Bluesky access"}))

(defn- ensure-session! []
  (or @session (create-session!)))

(defn- auth-headers []
  (let [sess (ensure-session!)]
    (if (:error sess)
      nil
      {"Authorization" (str "Bearer " (:access-jwt sess))})))

(defn- parse-post [post]
  (let [record (:record post)
        text (or (:text record) "")]
    {:title (if (> (count text) 120)
              (str (subs text 0 120) "...")
              text)
     :url (let [uri (:uri post)]
            (when uri
              (let [parts (str/split uri #"/")]
                (str "https://bsky.app/profile/"
                     (nth parts 2 "")
                     "/post/"
                     (last parts)))))
     :score (+ (or (:likeCount post) 0)
               (or (:repostCount post) 0))
     :comments (:replyCount post)
     :source :bluesky}))

(defn search-posts
  "Search Bluesky posts. Requires auth. Returns a vector of post maps or {:error}."
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int :days-back :int)] (schema/result Post)]}
  [query & {:keys [count days-back]
            :or {count 20}}]
  (let [headers (auth-headers)]
    (if (nil? headers)
      (ensure-session!) ;; returns the error map
      (let [params (cond-> {:q query
                            :limit (min count 25)}
                     days-back (assoc :since (str (intake/days-ago-iso days-back) "T00:00:00Z")))
            data (intake/fetch-json
                  (str pds-base "/xrpc/app.bsky.feed.searchPosts")
                  :headers headers
                  :query-params params)]
        (if (:error data)
          ;; On 401, clear session and retry once
          (if (str/includes? (str (:error data)) "401")
            (do (reset! session nil)
                (let [new-headers (auth-headers)]
                  (if (nil? new-headers)
                    (ensure-session!)
                    (let [data2 (intake/fetch-json
                                 (str pds-base "/xrpc/app.bsky.feed.searchPosts")
                                 :headers new-headers
                                 :query-params params)]
                      (if (:error data2)
                        data2
                        (->> (:posts data2) (mapv parse-post)))))))
            data)
          (->> (:posts data)
               (mapv parse-post)))))))
