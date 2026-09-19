(ns dvergr.intake.finnhub
  "Finnhub stock market data intake — quotes, earnings, news, insider trades.
   Free API key required: https://finnhub.io/
   Rate limit: 60 requests per minute (free tier).
   Set FINNHUB_API_KEY env var.

   Public fns return RAW data (maps / vectors of maps, or {:error \"…\"})."
  (:require [dvergr.intake.core :as intake]
            [dvergr.intake.schema :as schema]
            [clojure.string :as str]))

(def ^:private api-base "https://finnhub.io/api/v1")

(def ^:private Num "A JSON number." [:or :int :double])

(def Quote
  "A stock quote; :timestamp is epoch seconds."
  [:map [:current [:maybe Num]] [:high [:maybe Num]] [:low [:maybe Num]] [:open [:maybe Num]]
   [:previous-close [:maybe Num]] [:change [:maybe Num]] [:change-pct [:maybe Num]]
   [:timestamp [:maybe :int]]])

(def Profile
  "A company profile; :market-cap is in millions, :ipo a yyyy-MM-dd date."
  [:map [:name [:maybe :string]] [:country [:maybe :string]] [:exchange [:maybe :string]]
   [:industry [:maybe :string]] [:market-cap [:maybe Num]] [:shares [:maybe Num]]
   [:ipo [:maybe schema/IsoDate]] [:logo [:maybe schema/Url]] [:url [:maybe schema/Url]]
   [:ticker [:maybe :string]] [:currency [:maybe :string]]])

(def Earning
  "One quarter's earnings, actual vs estimate."
  [:map [:period [:maybe schema/IsoDate]] [:actual [:maybe Num]] [:estimate [:maybe Num]]
   [:surprise [:maybe Num]] [:surprise-pct [:maybe Num]] [:symbol [:maybe :string]]])

(def NewsArticle
  "One company news article; :datetime is epoch seconds."
  [:map [:headline [:maybe :string]] [:summary [:maybe :string]] [:source [:maybe :string]]
   [:url [:maybe schema/Url]] [:datetime [:maybe :int]] [:category [:maybe :string]]])

(def InsiderTransaction
  "One insider transaction; :transaction-type is Finnhub's transactionCode, the
   SEC Form 4 code (\"P\" purchase, \"S\" sale, \"M\" option exercise, ...)."
  [:map [:name [:maybe :string]] [:share [:maybe Num]] [:change [:maybe Num]]
   [:transaction-price [:maybe Num]] [:transaction-type [:maybe :string]]
   [:filing-date [:maybe schema/IsoDate]]])

(def BasicFinancials
  "Selected basic financial metrics; every value may be absent. :ev-fcf is
   current enterprise value / annual free cash flow (currentEv/freeCashFlowAnnual)."
  (into [:map] (for [k [:pe-annual :pb-annual :ps-annual :ev-fcf :dividend-yield :roe :roa
                        :gross-margin :operating-margin :net-margin :revenue-growth-3y
                        :revenue-growth-5y :eps-growth-3y :eps-growth-5y :52-week-high
                        :52-week-low :beta :market-cap]]
                 [k [:maybe Num]])))

(defn- api-key []
  (env/get "FINNHUB_API_KEY"))

(defn- finnhub-get
  "GET from Finnhub API with API key."
  [path & {:keys [params]}]
  (if-not (api-key)
    {:error "FINNHUB_API_KEY not set. Get a free key at https://finnhub.io/"}
    (intake/fetch-json (str api-base path)
                       :query-params (assoc (or params {}) :token (api-key)))))

(defn fetch-quote
  "Get current stock quote for a ticker symbol.
   Returns {:current :high :low :open :previous-close :change :change-pct :timestamp}."
  {:malli/schema [:=> [:cat :string] (schema/one Quote)]}
  [symbol]
  (let [data (finnhub-get "/quote" :params {:symbol (str/upper-case symbol)})]
    (if (:error data)
      data
      {:current        (:c data)
       :high           (:h data)
       :low            (:l data)
       :open           (:o data)
       :previous-close (:pc data)
       :change         (:d data)
       :change-pct     (:dp data)
       :timestamp      (:t data)})))

(defn fetch-company-profile
  "Get company profile for a ticker.
   Returns {:name :country :exchange :industry :market-cap :shares :ipo :logo :url :ticker}."
  {:malli/schema [:=> [:cat :string] (schema/one Profile)]}
  [symbol]
  (let [data (finnhub-get "/stock/profile2" :params {:symbol (str/upper-case symbol)})]
    (if (:error data)
      data
      (if (empty? data)
        {:error (str "No company profile found for " symbol)}
        {:name        (:name data)
         :country     (:country data)
         :exchange    (:exchange data)
         :industry    (:finnhubIndustry data)
         :market-cap  (:marketCapitalization data)
         :shares      (:shareOutstanding data)
         :ipo         (:ipo data)
         :logo        (:logo data)
         :url         (:weburl data)
         :ticker      (:ticker data)
         :currency    (:currency data)}))))

(defn fetch-earnings
  "Get quarterly earnings for a ticker (actual vs estimate).
   Returns [{:period :actual :estimate :surprise :surprise-pct}]."
  {:malli/schema [:=> [:cat :string (schema/kwargs :count :int)] (schema/result Earning)]}
  [symbol & {:keys [count] :or {count 4}}]
  (let [data (finnhub-get "/stock/earnings" :params {:symbol (str/upper-case symbol)
                                                      :limit count})]
    (if (:error data)
      data
      (->> data
           (mapv (fn [e]
                   {:period       (:period e)
                    :actual       (:actual e)
                    :estimate     (:estimate e)
                    :surprise     (:surprise e)
                    :surprise-pct (:surprisePercent e)
                    :symbol       (:symbol e)}))))))

(defn fetch-company-news
  "Get recent news articles about a company.
   Returns [{:headline :summary :source :url :datetime :category}]."
  {:malli/schema [:=> [:cat :string (schema/kwargs :days-back :int :count :int)] (schema/result NewsArticle)]}
  [symbol & {:keys [days-back count]
             :or {days-back 7 count 10}}]
  (let [from-date (intake/days-ago-iso days-back)
        to-date (intake/days-ago-iso 0)
        data (finnhub-get "/company-news" :params {:symbol (str/upper-case symbol)
                                                    :from from-date
                                                    :to to-date})]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv (fn [n]
                   {:headline (:headline n)
                    :summary  (:summary n)
                    :source   (:source n)
                    :url      (:url n)
                    :datetime (:datetime n)
                    :category (:category n)}))))))

(defn fetch-insider-transactions
  "Get insider transactions (buys/sells) for a ticker.
   Returns [{:name :share :change :transaction-price :transaction-type :filing-date}]."
  {:malli/schema [:=> [:cat :string] (schema/result InsiderTransaction)]}
  [symbol]
  (let [data (finnhub-get "/stock/insider-transactions" :params {:symbol (str/upper-case symbol)})]
    (if (:error data)
      data
      (->> (get data :data [])
           (take 20)
           (mapv (fn [t]
                   {:name              (:name t)
                    :share             (:share t)
                    :change            (:change t)
                    :transaction-price (:transactionPrice t)
                    :transaction-type  (:transactionCode t)
                    :filing-date       (:filingDate t)}))))))

(defn fetch-peers
  "Get list of peer/competitor ticker symbols for a company."
  {:malli/schema [:=> [:cat :string] (schema/result :string)]}
  [symbol]
  (finnhub-get "/stock/peers" :params {:symbol (str/upper-case symbol)}))

(defn fetch-basic-financials
  "Get basic financial metrics (P/E, P/B, margins, growth, etc.) for a ticker."
  {:malli/schema [:=> [:cat :string] (schema/one BasicFinancials)]}
  [symbol]
  (let [data (finnhub-get "/stock/metric" :params {:symbol (str/upper-case symbol)
                                                    :metric "all"})]
    (if (:error data)
      data
      (let [m (get data :metric {})]
        {:pe-annual         (get m :peBasicExclExtraTTM)
         :pb-annual         (get m :pbAnnual)
         :ps-annual         (get m :psAnnual)
         :ev-fcf            (get m :currentEv/freeCashFlowAnnual)
         :dividend-yield    (get m :dividendYieldIndicatedAnnual)
         :roe               (get m :roeTTM)
         :roa               (get m :roaTTM)
         :gross-margin      (get m :grossMarginTTM)
         :operating-margin  (get m :operatingMarginTTM)
         :net-margin        (get m :netProfitMarginTTM)
         :revenue-growth-3y (get m :revenueGrowth3Y)
         :revenue-growth-5y (get m :revenueGrowth5Y)
         :eps-growth-3y     (get m :epsGrowth3Y)
         :eps-growth-5y     (get m :epsGrowth5Y)
         :52-week-high      (get m :52WeekHigh)
         :52-week-low       (get m :52WeekLow)
         :beta              (get m :beta)
         :market-cap        (get m :marketCapitalization)}))))
