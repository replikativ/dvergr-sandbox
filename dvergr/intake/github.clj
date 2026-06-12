(ns dvergr.intake.github
  "GitHub intake via REST API (optional token for higher rate limits).
   Set GITHUB_TOKEN (or GITHUB_DVERGR_TOKEN) for higher rate limits.

   Public fns return RAW data (maps / vectors of maps, or {:error \"…\"})."
  (:require [dvergr.intake.core :as intake]
            [clojure.string :as str]))

(def ^:private api-base "https://api.github.com")

(defn- auth-headers []
  (let [token (or (env/get "GITHUB_TOKEN")
                  (env/get "GITHUB_DVERGR_TOKEN"))]
    (cond-> {"Accept" "application/vnd.github+json"}
      (not (str/blank? token)) (assoc "Authorization" (str "Bearer " token)))))

(defn- parse-repo [repo]
  {:title (str (:full_name repo) " - " (:description repo))
   :url (:html_url repo)
   :score (:stargazers_count repo)
   :source :github
   :tag (:language repo)
   :summary (str "Stars: " (:stargazers_count repo)
                 " | Forks: " (:forks_count repo)
                 (when (:language repo) (str " | " (:language repo))))})

(defn- parse-release [repo-name release]
  {:title (str repo-name " " (:tag_name release))
   :url (:html_url release)
   :source :github
   :summary (let [body (or (:body release) "")]
              (if (> (count body) 300)
                (str (subs body 0 300) "...")
                body))})

;; --- Trending repos ---

(defn fetch-trending
  "Fetch recently created repos sorted by stars."
  [& {:keys [language topic days-back count]
      :or {days-back 7 count 20}}]
  (let [date-filter (intake/days-ago-iso days-back)
        q (str "created:>" date-filter
               (when language (str " language:" language))
               (when topic (str " topic:" topic)))
        data (intake/fetch-json (str api-base "/search/repositories")
                                :headers (auth-headers)
                                :query-params {:q q
                                               :sort "stars"
                                               :order "desc"
                                               :per_page (min count 30)})]
    (if (:error data)
      data
      (->> (:items data)
           (mapv parse-repo)))))

;; --- Releases ---

(defn fetch-releases
  "Fetch recent releases for specified repos."
  [repos-str & {:keys [count] :or {count 5}}]
  (let [repos (str/split repos-str #",\s*")]
    (->> repos
         (mapcat (fn [repo]
                   (let [data (intake/fetch-json
                               (str api-base "/repos/" (str/trim repo) "/releases")
                               :headers (auth-headers)
                               :query-params {:per_page count})]
                     (if (:error data)
                       []
                       (->> data
                            (take count)
                            (mapv #(parse-release (str/trim repo) %)))))))
         vec)))

;; --- Search ---

(defn search-repos
  "Search GitHub repositories."
  [query & {:keys [language sort-by count]
            :or {sort-by "stars" count 20}}]
  (let [q (str query (when language (str " language:" language)))
        data (intake/fetch-json (str api-base "/search/repositories")
                                :headers (auth-headers)
                                :query-params {:q q
                                               :sort sort-by
                                               :order "desc"
                                               :per_page (min count 30)})]
    (if (:error data)
      data
      (->> (:items data)
           (mapv parse-repo)))))

;; --- User/Profile ---

(defn fetch-user
  "Fetch a GitHub user profile."
  [username]
  (let [data (intake/fetch-json (str api-base "/users/" username)
                                :headers (auth-headers))]
    (if (:error data)
      data
      {:login (:login data)
       :name (:name data)
       :company (:company data)
       :location (:location data)
       :bio (:bio data)
       :blog (:blog data)
       :twitter (:twitter_username data)
       :followers (:followers data)
       :following (:following data)
       :public-repos (:public_repos data)
       :url (:html_url data)})))

;; --- Contributors ---

(defn fetch-contributors
  "Fetch contributors for a repo. Returns [{:login :contributions :url}]."
  [repo & {:keys [count] :or {count 30}}]
  (let [data (intake/fetch-json (str api-base "/repos/" repo "/contributors")
                                :headers (auth-headers)
                                :query-params {:per_page (min count 100)})]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv (fn [c]
                   {:login (:login c)
                    :contributions (:contributions c)
                    :url (:html_url c)
                    :avatar (:avatar_url c)}))))))

;; --- Code Search ---

(defn search-code
  "Search GitHub code. Returns [{:path :repo :url}].
   Supports :filename filter, e.g. (search-code \"org.example\" :filename \"deps.edn\")."
  [query & {:keys [language filename count] :or {count 20}}]
  (let [q (str query
               (when language (str " language:" language))
               (when filename (str " filename:" filename)))
        data (intake/fetch-json (str api-base "/search/code")
                                :headers (auth-headers)
                                :query-params {:q q
                                               :per_page (min count 30)})]
    (if (:error data)
      data
      (->> (:items data)
           (take count)
           (mapv (fn [item]
                   {:path (:path item)
                    :repo (get-in item [:repository :full_name])
                    :url (:html_url item)}))))))

;; --- Org Members ---

(defn fetch-org-members
  "Fetch public members of a GitHub organization."
  [org & {:keys [count] :or {count 30}}]
  (let [data (intake/fetch-json (str api-base "/orgs/" org "/members")
                                :headers (auth-headers)
                                :query-params {:per_page (min count 100)})]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv (fn [m] {:login (:login m) :url (:html_url m)}))))))

;; --- Repo Issues/Discussions ---

(defn fetch-issues
  "Fetch recent issues for a repo."
  [repo & {:keys [state count] :or {state "open" count 20}}]
  (let [data (intake/fetch-json (str api-base "/repos/" repo "/issues")
                                :headers (auth-headers)
                                :query-params {:state state
                                               :sort "updated"
                                               :direction "desc"
                                               :per_page (min count 30)})]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv (fn [i]
                   {:number (:number i)
                    :title (:title i)
                    :state (:state i)
                    :user (get-in i [:user :login])
                    :url (:html_url i)
                    :created (:created_at i)
                    :updated (:updated_at i)
                    :comments (:comments i)
                    :labels (mapv :name (:labels i))}))))))

;; --- Repo details ---

(defn fetch-repo-details
  "Fetch full repo details including stats."
  [repo]
  (let [data (intake/fetch-json (str api-base "/repos/" repo)
                                :headers (auth-headers))]
    (if (:error data)
      data
      {:name (:full_name data)
       :description (:description data)
       :stars (:stargazers_count data)
       :forks (:forks_count data)
       :open-issues (:open_issues_count data)
       :language (:language data)
       :topics (:topics data)
       :created (:created_at data)
       :updated (:updated_at data)
       :pushed (:pushed_at data)
       :license (get-in data [:license :spdx_id])
       :homepage (:homepage data)
       :url (:html_url data)})))
