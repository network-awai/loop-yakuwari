#!/usr/bin/env nbb
;; awai — read the registry, check it, project it, tick it.
;;
;; nbb, not bash: ADR-2607173000 retires `bb` as a script host and CLAUDE.md
;; prohibits new .sh files outright. The pure half lives in src/awai/*.cljc;
;; this file is only I/O and argument handling, so the deciding logic stays
;; testable without a filesystem.
;;
;;   nbb bin/awai.cljs check              # cross-file agreement; exit 1 on drift
;;   nbb bin/awai.cljs roles              # every role, one line each
;;   nbb bin/awai.cljs ceiling            # where fleet.edn narrows a role
;;   nbb bin/awai.cljs identities         # the person-* repos the registry expects
;;   nbb bin/awai.cljs project            # the display model, as EDN
;;   nbb bin/awai.cljs tick [--apply]     # one loop cycle; dry-run by default

(ns awai.cli
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            [awai.registry :as registry]
            [awai.loop :as loop']
            [yakuwari-view.model :as view]))

(defn- find-root
  "Walk up from `start` looking for fleet.edn.

  A LaunchAgent starts with cwd `/`, so cwd alone is not enough, and nbb's
  own argv[1] is the nbb entrypoint rather than this script — deriving the
  root from either one alone silently resolves somewhere else and then
  reports an empty registry, which is the failure mode that looks like
  success. `AWAI_ROOT` wins when set so the plist can be explicit."
  [start]
  (or (some-> (.. js/process -env -AWAI_ROOT))
      (loop [d (path/resolve start) depth 0]
        (cond
          (fs/existsSync (path/join d "fleet.edn")) d
          (or (> depth 8) (= d (path/dirname d)))
          (throw (ex-info (str "no fleet.edn found above " start
                               " — set AWAI_ROOT") {:start start}))
          :else (recur (path/dirname d) (inc depth))))))

(def repo-root (find-root (.cwd js/process)))

(defn- at [& parts] (apply path/join repo-root parts))

(defn- read-edn [p]
  (edn/read-string {:default (fn [_ v] v)} (str (fs/readFileSync p "utf8"))))

(defn- role-files []
  (->> (fs/readdirSync (at "yakuwari"))
       (map str)
       (filter #(str/ends-with? % ".edn"))
       sort
       (mapv #(at "yakuwari" %))))

(defn load-registry
  "Read every file. Reports what it skipped and why — a corpus that hides
  what it could not read reads as a corpus with nothing to hide, which is
  the failure `warn-skipped!` exists to prevent on the superproject query
  plane."
  []
  (let [skipped (atom [])
        roles (vec (mapcat (fn [f]
                             (try
                               (let [c (read-edn f)]
                                 (if (and (vector? c) (every? map? c))
                                   c
                                   (do (swap! skipped conj
                                              {:file (path/basename f)
                                               :why :not-a-vector-of-maps})
                                       [])))
                               (catch :default e
                                 (swap! skipped conj
                                        {:file (path/basename f)
                                         :why :parse-error
                                         :detail (.-message e)})
                                 [])))
                           (role-files)))]
    (doseq [s @skipped]
      (binding [*print-fn* *print-err-fn*]
        (println (str "WARNING skipped " (:file s) " — " (name (:why s))
                      (when (:detail s) (str ": " (:detail s)))))))
    {:fleet (read-edn (at "fleet.edn"))
     :businesses (read-edn (at "businesses.edn"))
     :roles roles
     :skipped @skipped}))

(defn- die [code] (.exit js/process code))

;; ---------------------------------------------------------------------------

(defn cmd-check []
  (let [reg (load-registry)
        r (registry/validate-fleet reg)
        {:keys [businesses roles outward narrowed-by-ceiling]} (:counts r)]
    (println (str "businesses " businesses " | roles " roles
                  " | outward " outward
                  " | narrowed by ceiling " narrowed-by-ceiling))
    (if (:ok? r)
      (println "check: OK")
      (do (println (str "check: FAIL — " (count (:problems r)) " problem(s)"))
          (doseq [p (:problems r)]
            (println (str "  " (name (:problem p))
                          "  " (or (:role/id p) (:business p))
                          (when (:kind p) (str " / " (name (:kind p))))
                          (when (:detail p) (str "  " (:detail p)))
                          (when (:problems p) (str "  " (pr-str (:problems p)))))))))
    ;; A skipped file is a failure even when everything that DID load agrees.
    (when (seq (:skipped reg))
      (println (str "check: " (count (:skipped reg)) " file(s) skipped")))
    (die (if (and (:ok? r) (empty? (:skipped reg))) 0 1))))

(defn cmd-roles []
  (let [{:keys [roles]} (load-registry)]
    (doseq [r (sort-by (juxt (comp str :yakuwari/business) (comp str :yakuwari/id)) roles)]
      (println (str (if (:yakuwari/outward? r) "→ " "  ")
                    (:yakuwari/id r)
                    "  desired " (:desired (:yakuwari/scale r))
                    "/max " (:max (:yakuwari/scale r))
                    "  caps " (count (:yakuwari/capabilities r))
                    (when-let [m (:yakuwari/mailbox r)] (str "  " m)))))
    (println (str "-- " (count roles) " roles, "
                  (count (filter :yakuwari/outward? roles)) " outward"))))

(defn cmd-ceiling []
  (let [{:keys [fleet roles]} (load-registry)
        rows (for [r roles
                   n (registry/ceiling-narrowings fleet r)]
               (assoc n :role (:yakuwari/id r)))]
    (if (seq rows)
      (doseq [n rows]
        (println (str (:role n) "  " (:capability n)
                      "  role says " (:role-says n)
                      " → fleet says " (:fleet-says n))))
      (println "no role claims more than the fleet ceiling allows"))))

(defn cmd-identities []
  (let [{:keys [roles]} (load-registry)]
    (doseq [i (sort-by :identity/repo (registry/outward-identities roles))]
      (println (str (:identity/repo i) "  " (:identity/mailbox i)
                    "  (" (:identity/role i) ")")))
    (println (str "-- " (count (registry/outward-identities roles))
                  " person-* repos expected"))))

(defn- runs
  "Live runs. No dispatcher is wired yet, so this is empty and says so rather
  than inventing rows — a projection built from fabricated runs would make
  the whole surface a lie."
  []
  (let [p (at "journal" "runs.edn")]
    (if (fs/existsSync p) (read-edn p) [])))

(defn cmd-project []
  (let [{:keys [businesses roles]} (load-registry)
        p (view/project {:roles roles
                         :runs (runs)
                         :groups (registry/groups businesses)
                         :group-key :yakuwari/business
                         :now-ms (.now js/Date)})]
    (prn p)))

(defn last-dispatch
  "When each role was last dispatched, folded from the journal.

  Without this the `:oldest-dispatch` tie-break is decoration: on a cold
  start every role scores the same, the secondary sort key is uniformly 0,
  and ordering falls through to alphabetical — so `:app-aozora/director`
  would win every tick forever and five businesses would never run. Measured:
  that is exactly what the first dry-run did.

  Absent from the journal means never dispatched, which sorts first. A role
  that has never run should outrank one that ran an hour ago."
  []
  (let [p (at "journal" "current.edn")]
    (if-not (fs/existsSync p)
      {}
      (->> (str/split-lines (str (fs/readFileSync p "utf8")))
           (remove str/blank?)
           (remove #(str/starts-with? (str/trim %) ";"))
           (keep (fn [line]
                   (try (edn/read-string {:default (fn [_ v] v)} line)
                        (catch :default _ nil))))
           (reduce (fn [acc entry]
                     (reduce (fn [a role-id]
                               ;; Later entries win: the journal is appended in
                               ;; tick order, so the last mention is the most
                               ;; recent dispatch.
                               (assoc a role-id (:tick/at entry)))
                             acc
                             (:tick/dispatched entry)))
                   {})))))

(defn cmd-tick [apply?]
  (let [{:keys [fleet roles]} (load-registry)
        t (loop'/tick {:fleet fleet :roles roles :runs (runs)
                       :now-ms (.now js/Date)
                       :last-dispatch (last-dispatch)})
        d (:decision t)]
    (println (str "active " (:active d) "/" (:global-wip d)
                  "  free " (:free d)
                  "  allowance " (:allowance d)
                  "  dispatch " (mapv :role/id (:dispatch d))))
    (doseq [[reason n] (frequencies (map :reason (:withheld d)))]
      (println (str "  withheld " n " × " (name reason))))
    (when-let [inv (seq (:tick/invalid (:evidence t)))]
      (println (str "  INVALID: " (str/join ", " (map str inv)))))
    (if apply?
      ;; The journal is append-only and the current window is committed to
      ;; git, so a tick's evidence is queryable without fetching anything.
      (let [p (at "journal" "current.edn")]
        (fs/appendFileSync p (str (pr-str (:evidence t)) "\n"))
        (println (str "recorded → journal/current.edn")))
      (println "dry-run: nothing dispatched, nothing recorded (--apply to act)"))))

(defn -main [& args]
  (let [[cmd & rest'] args
        apply? (boolean (some #{"--apply"} rest'))]
    (case cmd
      "check" (cmd-check)
      "roles" (cmd-roles)
      "ceiling" (cmd-ceiling)
      "identities" (cmd-identities)
      "project" (cmd-project)
      "tick" (cmd-tick apply?)
      (do (println "usage: awai <check|roles|ceiling|identities|project|tick>")
          (die 2)))))

;; nbb passes script arguments as *command-line-args*; js/process.argv[1] is
;; nbb's own entrypoint, not this file.
(apply -main *command-line-args*)
