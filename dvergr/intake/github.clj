(ns dvergr.intake.github
  "GitHub intake via REST API (optional token for higher rate limits).
   Set GITHUB_TOKEN (or GITHUB_DVERGR_TOKEN) for higher rate limits.

   Public fns return RAW data (maps / vectors of maps, or {:error \"…\"})."
  (:require [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [clojure.string :as str]))

(def ^:private api-base "https://api.github.com")

(def Repo
  "One repository as the search/trending fns return it."
  [:map [:title :string] [:url [:maybe schema/Url]] [:score [:maybe :int]]
   [:source [:= :github]] [:tag [:maybe :string]] [:summary :string]])

(def Release
  "One release of a repo, body truncated to 300 chars as :summary."
  [:map [:title :string] [:url [:maybe schema/Url]] [:source [:= :github]]
   [:summary :string]])

(def User
  "A GitHub user profile."
  [:map [:login [:maybe :string]] [:name [:maybe :string]] [:company [:maybe :string]]
   [:location [:maybe :string]] [:bio [:maybe :string]] [:blog [:maybe :string]]
   [:twitter [:maybe :string]] [:followers [:maybe :int]] [:following [:maybe :int]]
   [:public-repos [:maybe :int]] [:url [:maybe schema/Url]]])

(def Contributor
  "One contributor of a repo."
  [:map [:login [:maybe :string]] [:contributions [:maybe :int]]
   [:url [:maybe schema/Url]] [:avatar [:maybe schema/Url]]])

(def CodeHit
  "One code-search hit."
  [:map [:path [:maybe :string]] [:repo [:maybe :string]] [:url [:maybe schema/Url]]])

(def Member
  "One public member of an organization."
  [:map [:login [:maybe :string]] [:url [:maybe schema/Url]]])

(def Issue
  "One issue (or pull request) of a repo."
  [:map [:number [:maybe :int]] [:title [:maybe :string]] [:state [:maybe :string]]
   [:user [:maybe :string]] [:url [:maybe schema/Url]] [:created [:maybe schema/IsoDate]]
   [:updated [:maybe schema/IsoDate]] [:comments [:maybe :int]] [:labels [:vector [:maybe :string]]]])

(def RepoDetails
  "Full details of one repository."
  [:map [:name [:maybe :string]] [:description [:maybe :string]] [:stars [:maybe :int]]
   [:forks [:maybe :int]] [:open-issues [:maybe :int]] [:language [:maybe :string]]
   [:topics [:maybe [:vector :string]]] [:created [:maybe schema/IsoDate]]
   [:updated [:maybe schema/IsoDate]] [:pushed [:maybe schema/IsoDate]]
   [:license [:maybe :string]] [:homepage [:maybe :string]] [:url [:maybe schema/Url]]])

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
  {:malli/schema [:=> [:cat (schema/kwargs :language :string :topic :string :days-back :int :count :int)] (schema/result Repo)]}
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
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int)] [:vector Release]]}
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
  {:malli/schema [:=> [:cat :string (schema/kwargs :language :string :sort-by :string :count :int)] (schema/result Repo)]}
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
  {:malli/schema [:=> [:cat :string] (schema/one User)]}
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
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int)] (schema/result Contributor)]}
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
  {:malli/schema [:=> [:cat :string (schema/kwargs :language :string :filename :string :count :int)] (schema/result CodeHit)]}
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
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int)] (schema/result Member)]}
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
  {:malli/schema [:=> [:cat :string (schema/kwargs :state :string :count :int)] (schema/result Issue)]}
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
  {:malli/schema [:=> [:cat :string] (schema/one RepoDetails)]}
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
