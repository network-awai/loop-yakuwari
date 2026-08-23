;; The test entry point. `npm test` runs this.
;;
;; It exists because the runner it replaces could not fail. `package.json`
;; used to read the return value of `clojure.test/run-tests`, which on nbb
;; (cljs.test) is nil -- the summary goes to `report`, not to the caller --
;; so `(+ (:fail nil) (:error nil))` was 0 and the exit code was 0 whatever
;; the tests said. Measured 2026-08-23 with a test asserting (= 1 2): FAIL
;; printed, exit 0. A check that cannot fail is not a check (CLAUDE.md, the
;; six questions), so the summary is taken from where cljs.test puts it.
;;
;; Exit codes: 0 every test passed; 1 a failure or error; 2 no test ran at
;; all -- an empty run is not a green run.
(ns run-tests
  (:require [clojure.test :as t]
            [awai.core-test]
            [awai.etzhayyim-parity-test]))

(def summary (atom nil))

(defmethod t/report [:cljs.test/default :summary] [m]
  (reset! summary m)
  (println "\nRan" (:test m) "tests containing"
           (+ (:pass m) (:fail m) (:error m)) "assertions.")
  (println (:fail m) "failures," (:error m) "errors."))

(t/run-tests 'awai.core-test 'awai.etzhayyim-parity-test)

(let [{:keys [test fail error] :as m} @summary]
  (.exit js/process
         (cond
           (nil? m) 2
           (zero? test) 2
           (pos? (+ fail error)) 1
           :else 0)))
