(ns dvergr.intake.crt-sh
  "Certificate Transparency log search via crt.sh. Free, no API key.
   Discovers subdomains and web properties for any domain by searching SSL
   certificate records — revealing a company's full digital footprint:
   internal tools, staging environments, product names, acquired domains."
  (:require [dvergr.intake.core :as intake]
            [clojure.string :as str]))

(defn search-certificates
  "Search Certificate Transparency logs for certificates matching a domain.
   Use %.domain.com to find all subdomains. kwargs: :count (default 100).
   Returns [{:id :issuer :name :not-before :not-after :entry-timestamp}]
   or {:error}."
  [query & {:keys [count] :or {count 100}}]
  (let [data (intake/fetch-json "https://crt.sh/"
                                :query-params {:q query :output "json"}
                                :timeout 30000)]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv (fn [cert]
                   {:id              (:id cert)
                    :issuer          (:issuer_name cert)
                    :name            (:name_value cert)
                    :common-name     (:common_name cert)
                    :not-before      (:not_before cert)
                    :not-after       (:not_after cert)
                    :serial          (:serial_number cert)
                    :entry-timestamp (:entry_timestamp cert)}))))))

(defn discover-subdomains
  "Discover all subdomains for a domain via Certificate Transparency.
   Returns a sorted vector of unique subdomain names or {:error}."
  [domain]
  (let [certs (search-certificates (str "%." domain) :count 500)]
    (if (:error certs)
      certs
      (->> certs
           (mapcat (fn [cert]
                     (str/split (or (:name cert) "") #"\n")))
           (remove str/blank?)
           (remove #(str/starts-with? % "*"))
           (map str/lower-case)
           distinct
           sort
           vec))))

(defn analyze-subdomains
  "Discover subdomains and categorize them by likely purpose.
   Returns {:domain :total :subdomains :categories {:internal [...] ...}}
   or {:error}."
  [domain]
  (let [subs (discover-subdomains domain)]
    (if (:error subs)
      subs
      (let [categorize (fn [sub]
                         (cond
                           (re-find #"(?i)blog|news|press|media" sub) :content
                           (re-find #"(?i)api|rest|graphql|ws\." sub) :api
                           (re-find #"(?i)app|portal|dashboard|admin|console" sub) :application
                           (re-find #"(?i)staging|stage|dev|test|qa|uat|pre-prod|sandbox" sub) :staging
                           (re-find #"(?i)mail|smtp|imap|pop|mx|email" sub) :email
                           (re-find #"(?i)cdn|static|assets|img|images|media" sub) :cdn
                           (re-find #"(?i)docs|doc|help|support|kb|faq|wiki" sub) :documentation
                           (re-find #"(?i)shop|store|buy|pay|checkout|cart" sub) :commerce
                           (re-find #"(?i)auth|login|sso|oauth|id\." sub) :auth
                           (re-find #"(?i)ir\.|investor|finance|sec" sub) :investor-relations
                           (re-find #"(?i)career|jobs|hire|recruit|talent" sub) :careers
                           (re-find #"(?i)vpn|internal|intra|corp" sub) :internal
                           (re-find #"(?i)ci|cd|jenkins|build|deploy|git" sub) :devops
                           (re-find #"(?i)monitor|status|health|metrics|grafana" sub) :monitoring
                           (re-find #"(?i)demo|trial|free|signup" sub) :demo
                           (re-find #"(?i)learn|academy|training|course|edu" sub) :learning
                           :else :other))
            categorized (group-by categorize subs)]
        {:domain domain
         :total (count subs)
         :subdomains subs
         :categories (into {} (map (fn [[k v]] [k (vec v)]) categorized))}))))
