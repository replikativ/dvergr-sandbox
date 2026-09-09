(require '[clojure.test :as t] 'dvergr.intake.provenance-test)
(let [{:keys [fail error]} (t/run-tests 'dvergr.intake.provenance-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
