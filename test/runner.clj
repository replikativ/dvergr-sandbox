(require 'sandbox-stubs '[clojure.test :as t] 'dvergr.intake.provenance-test 'dvergr.intake.evidence-test 'dvergr.intake.schema-test 'dvergr.intake.shapes-a-test 'dvergr.intake.shapes-b-test 'dvergr.intake.shapes-c-test)
(let [{:keys [fail error]} (t/run-tests 'dvergr.intake.provenance-test 'dvergr.intake.evidence-test 'dvergr.intake.schema-test 'dvergr.intake.shapes-a-test 'dvergr.intake.shapes-b-test 'dvergr.intake.shapes-c-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
