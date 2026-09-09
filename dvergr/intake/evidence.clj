(ns dvergr.intake.evidence
  "Pure evidence selection over saved HTTP/intake responses. No IO, mutable
   state, access to audit stores, or authority to certify evidence.")

(defn quote-span
  "Select an exact, nonempty [start,end) span from response :body or :text.
   Offsets use Clojure string indices (UTF-16 code units). Returns :quote,
   :selection {:field :start :end :unit}, optional :url, and unchanged host
   acquisition/fixture markers. Retain the original response for later spans.

   Requires a UUID acquisition id, string field and valid offsets; rejects
   error responses and explicit non-2xx status. Missing receipts are not
   invented. :capture may still be :disabled: this helper does not promise a
   stored body. A span of extracted/truncated :text is NOT a span of the original
   HTTP :body. Supplied values/markers remain untrusted claims; host verification
   must check scope, acquisition, extraction provenance and claim support."
  [response field start end]
  (let [text (get response field)
        status (:status response)]
    (when-not (and (map? response) (#{:body :text} field) (string? text)
                   (not (contains? response :error))
                   (or (not (contains? response :status))
                       (and (integer? status) (<= 200 status 299)))
                   (uuid? (get-in response [:dvergr/acquisition :id]))
                   (integer? start) (integer? end) (<= 0 start) (< start end)
                   (<= end (count text)))
      (throw (ex-info "Expected a recorded successful response and a valid nonempty text span"
                      {:type ::invalid-span :field field :start start :end end})))
    (assoc (select-keys response [:url :dvergr/acquisition :dvergr/fixture-id])
           :quote (subs text start end)
           :selection {:field field :start start :end end :unit :utf-16})))
