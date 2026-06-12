(ns dvergr.intake.web-fetch
  "Generic URL fetcher — strips HTML to readable text.
   Suitable for articles, blog posts, docs, any public web page.
   YouTube and Twitter have dedicated intakes.

   Built over the sandbox `http` + `html` primitives: `http/get` follows
   redirects by default, `codec/strip-tags` + `codec/decode-entities` turn a
   page into plain text — no host libs, no regex tag-stripping needed."
  (:require [babashka.http-client :as http] [cheshire.core :as json] [dvergr.codec :as codec] [clojure.string :as str]))

(def ^:private browser-ua
  "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0 (dvergr intake)")

(defn- strip-html
  "Remove HTML markup and decode basic entities, returning readable plain text."
  [html-str]
  (-> html-str
      codec/strip-tags
      codec/decode-entities
      (str/replace #"\s+" " ")
      str/trim))

(defn fetch-page
  "Fetch a URL and return its text content.
   Returns {:url :text :title} or {:url :error}."
  [url & {:keys [max-chars] :or {max-chars 8000}}]
  (try
    (let [resp (http/get url
                         {:headers {"User-Agent"      browser-ua
                                    "Accept"          "text/html,application/xhtml+xml,text/plain"
                                    "Accept-Language" "en-US,en;q=0.9"}
                          :timeout 15000})
          ;; body may be auto-parsed to a map for JSON responses; coerce to string
          raw  (:body resp)
          body (if (string? raw) raw (json/encode raw))
          ct   (str/lower-case (get-in resp [:headers "content-type"] ""))
          text (if (str/includes? ct "html") (strip-html body) (str/trim body))
          text (if (> (count text) max-chars)
                 (str (subs text 0 max-chars) "\n\n[content truncated]")
                 text)
          title (some-> (re-find #"(?i)<title[^>]*>([^<]+)</title>" body) second str/trim)]
      {:url url :text text :title title})
    (catch Throwable e
      {:url url
       :error (or (some->> (:status (ex-data e)) (str "HTTP "))
                  (.getMessage e))})))
