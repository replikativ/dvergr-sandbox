(ns dvergr.intake.zulip
  "Zulip intake — fetch messages and search across Clojurians Zulip streams.

   Uses the Zulip REST API with Basic auth (bot email + API key).

   PORT-NOTE: the native namespace read config from config.local.edn's :zulip
   key {:email :api-key :site}. The sandbox has no config access, so this port
   reads the same three values from env vars instead:
     ZULIP_EMAIL, ZULIP_API_KEY, ZULIP_SITE
   (e.g. ZULIP_SITE=\"https://clojurians.zulipchat.com\").

   PORT-NOTE: the http primitive has no :basic-auth — the Authorization header
   is built by hand with codec/base64-encode."
  (:require [cheshire.core :as json] [dvergr.codec :as codec] [dvergr.intake.core :as intake]))

;; ============================================================================
;; Zulip API
;; ============================================================================

;; `site` is a non-secret endpoint (returned verbatim — configure via `:sandbox-env
;; {"ZULIP_SITE" "https://…zulipchat.com"}`). `auth` is the PRE-ENCODED
;; `base64(email:api-key)` — for a real deployment an opaque placeholder the gated
;; `http` primitive substitutes at egress (boundary secret injection), so the key
;; is never in the sandbox. Configure via a `:secrets` entry with
;; `:basic-auth-config-paths [[:zulip :email] [:zulip :api-key]]`.
(defn- zulip-config []
  {:site (env/get "ZULIP_SITE")
   :auth (env/get "ZULIP_AUTH")})

(defn- zulip-get
  "GET a Zulip API endpoint with Basic auth. Returns parsed JSON or {:error ...}."
  [path & {:keys [query-params]}]
  (let [{:keys [site auth]} (zulip-config)]
    (when-not (and site auth)
      (throw (ex-info "Zulip not configured. Set :sandbox-env ZULIP_SITE + a :secrets ZULIP_AUTH" {})))
    (intake/fetch-json (str site "/api/v1" path)
                       :headers {"Authorization" (str "Basic " auth)}
                       :query-params query-params)))

;; ============================================================================
;; Data Access
;; ============================================================================

(defn fetch-streams
  "List public Zulip streams (channels). Returns vec of stream maps or {:error ...}."
  []
  (let [data (zulip-get "/streams")]
    (if (:error data)
      data
      (->> (:streams data)
           (filter #(not (:invite_only %)))
           (mapv (fn [s]
                   {:name        (:name s)
                    :stream-id   (:stream_id s)
                    :description (:description s)
                    :subscribers (:subscribers s)}))))))

(defn fetch-topics
  "List topics in a stream. Returns vec of topic maps or {:error ...}."
  [stream-id]
  (let [data (zulip-get (str "/users/me/" stream-id "/topics"))]
    (if (:error data)
      data
      (->> (:topics data)
           (mapv (fn [t]
                   {:name       (:name t)
                    :max-id     (:max_id t)}))))))

(defn- parse-message [msg]
  {:id        (:id msg)
   :sender    (:sender_full_name msg)
   :stream    (:display_recipient msg)
   :topic     (:subject msg)
   :content   (:content msg)
   :timestamp (:timestamp msg)
   :url       (str (:site (zulip-config))
                    "/#narrow/stream/" (codec/url-encode (str (:display_recipient msg)))
                    "/topic/" (codec/url-encode (str (:subject msg)))
                    "/near/" (:id msg))})

(defn fetch-messages
  "Fetch recent messages from a stream, optionally filtered by topic.
   Returns vec of message maps or {:error ...}."
  [stream & {:keys [topic count] :or {count 20}}]
  (let [narrow (cond-> [{"operator" "channel" "operand" stream}]
                 topic (conj {"operator" "topic" "operand" topic}))
        data   (zulip-get "/messages"
                          :query-params {"anchor"         "newest"
                                         "num_before"     (str count)
                                         "num_after"      "0"
                                         "narrow"         (json/encode narrow)
                                         "apply_markdown" "false"})]
    (if (:error data)
      data
      (->> (:messages data)
           (mapv parse-message)))))

(defn search-messages
  "Search messages across all streams. Returns vec of message maps or {:error ...}."
  [query & {:keys [stream count] :or {count 20}}]
  (let [narrow (cond-> [{"operator" "search" "operand" query}]
                 stream (conj {"operator" "channel" "operand" stream}))
        data   (zulip-get "/messages"
                          :query-params {"anchor"         "newest"
                                         "num_before"     (str count)
                                         "num_after"      "0"
                                         "narrow"         (json/encode narrow)
                                         "apply_markdown" "false"})]
    (if (:error data)
      data
      (->> (:messages data)
           (mapv parse-message)))))
