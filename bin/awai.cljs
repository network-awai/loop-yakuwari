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
;;   nbb bin/awai.cljs runs               # what currently occupies a slot
;;   nbb bin/awai.cljs sync               # refresh run statuses from tamaki
;;   nbb bin/awai.cljs dispatch [--apply] # tick, then PERFORM its effects

(ns awai.cli
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [awai.registry :as registry]
            [awai.loop :as loop']
            [awai.dispatch :as dispatch]
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
  "Live runs — what currently occupies a slot.

  Written by `dispatch --apply` and refreshed by `sync`. Empty until the
  dispatcher has run, and empty is reported as empty: a projection built from
  fabricated rows would make the whole surface a lie.

  Terminal runs are pruned on write, so this file answers 'what is live'
  rather than 'what ever happened' — that history is tamaki's event store."
  []
  (let [p (at "journal" "runs.edn")]
    (if (fs/existsSync p) (vec (read-edn p)) [])))

(defn- write-runs! [rs]
  (fs/writeFileSync (at "journal" "runs.edn")
                    (str ";; Live AgentRuns dispatched from this fleet. Generated by\n"
                         ";; `awai dispatch --apply` / `awai sync` — not hand-edited.\n"
                         ";; Terminal runs are pruned; tamaki's event store is the history.\n"
                         (pr-str (vec rs)) "\n")))

;; ---------------------------------------------------------------------------
;; tamaki, the executing half
;; ---------------------------------------------------------------------------

(defn- workspace
  "The superproject checkout that holds tamaki and the business repos.

  `AWAI_WORKSPACE` wins; otherwise walk up looking for `manifest/west.yml`,
  which is the superproject's own marker. Deriving it from this repo's depth
  instead would break the moment the loop runs from a worktree, which is
  exactly where it gets developed."
  []
  (or (.. js/process -env -AWAI_WORKSPACE)
      (loop [d repo-root depth 0]
        (cond
          (fs/existsSync (path/join d "manifest" "west.yml")) d
          (or (> depth 8) (= d (path/dirname d))) nil
          :else (recur (path/dirname d) (inc depth))))))

(defn- dispatch-config [fleet]
  (dispatch/config fleet
                   (cond-> {:workspace (workspace)}
                     ;; Machine-local like the workspace, and for the same
                     ;; reason: a checkout path is not fleet policy.
                     (.. js/process -env -AWAI_WORKTREE_ROOT)
                     (assoc :worktree-root (.. js/process -env -AWAI_WORKTREE_ROOT)))))

(defn- tamaki-bin [cfg]
  (when-let [ws (:workspace cfg)] (path/join ws (:tamaki cfg))))

(defn- tamaki!
  "Run tamaki and read its EDN back. tamaki pretty-prints one form to stdout,
  so a non-zero exit or an unparseable body is returned as data rather than
  thrown — a dispatch that fails on one role must still cancel and record the
  rest of the tick."
  [cfg argv]
  (let [bin (tamaki-bin cfg)]
    (if-not (and bin (fs/existsSync bin))
      {:ok? false :why :tamaki-not-found :detail bin}
      ;; The body is parsed whatever the exit code says. `tamaki doctor`
      ;; reports 'not ready' BY exiting non-zero, and its stdout is the only
      ;; place that names which component is missing — treating a non-zero
      ;; exit as an unreadable result would throw away the diagnosis and leave
      ;; every failure looking the same.
      (let [r (cp/spawnSync bin (clj->js argv)
                            #js {:encoding "utf8" :maxBuffer (* 16 1024 1024)})
            code (.-status r)
            value (try (edn/read-string {:default (fn [_ v] v)} (str (.-stdout r)))
                       (catch :default _ nil))]
        (cond
          (and (zero? code) (nil? value))
          {:ok? false :why :unreadable-output :code code
           :detail (str/trim (str (.-stderr r)))}
          (zero? code) {:ok? true :code code :value value}
          :else {:ok? false :why :tamaki-exit :code code :value value
                 :detail (str/trim (str (.-stderr r)))})))))

(defn- runtime-ready
  "Whether tamaki can actually execute in this mode, asked BEFORE anything is
  submitted.

  tamaki checks the same thing in `execute-run!` — but we start runs detached,
  so its answer arrives on a stream nobody is reading. A run submitted into a
  runtime that cannot start it stays `:queued` and holds its slot until a
  human notices, and six of those stop the fleet. Asking first turns that into
  one line of output.

  Measured 2026-08-07: `:kotoba-code {:ok? false}` on this machine — the
  checkout has `bin/claude` but no `bin/kotoba-code`, so `:local` mode has no
  executor at all. That is the condition this check exists for."
  [cfg]
  (let [res (tamaki! cfg ["doctor"])]
    (if-not (map? (:value res))
      {:ready? false :why (or (:why res) :no-doctor-report) :detail (:detail res)}
      (let [d (:value res)
            needed (case (:mode cfg) :local [:event-store :kotoba-code] [:event-store])
            missing (vec (remove #(get-in d [% :ok?]) needed))]
        (if (seq missing)
          {:ready? false :why :runtime-not-ready :missing missing
           :detail (str/join ", " (map #(str (name %) " → "
                                             (get-in d [% :path] "unavailable"))
                                       missing))}
          {:ready? true})))))

(defn- git!
  [dir argv]
  (let [r (cp/spawnSync "git" (clj->js (into ["-C" dir] argv))
                        #js {:encoding "utf8" :maxBuffer (* 8 1024 1024)})]
    {:ok? (zero? (.-status r))
     :out (str/trim (str (.-stdout r)))
     :err (str/trim (str (.-stderr r)))}))

(defn- prepare-worktree!
  "An isolated working tree for one run, branched from the business repo's
  live default branch.

  Three properties this has to hold at once, and each one is a rule that was
  already written down before this dispatcher existed:

  - **Never the shared checkout.** tamaki's local mode runs the agent in
    `--project` itself, so pointing it at `orgs/<org>/<repo>` would let a
    parallel session's `git checkout` revert uncommitted work.
  - **Never a stale base.** West checkouts sit detached at a pin with no
    remote-tracking refs, so branching from HEAD would silently build on
    whatever the pin last said. The default branch is fetched first
    (CLAUDE.md: sync BEFORE the branch exists, not before the push).
  - **Outside the superproject.** `:worktree-root` is machine-local and
    belongs on a sibling path, or west resolves `.west/` upward and decides
    the topdir is the superproject after all.

  Returns `{:ok? false :why ...}` rather than a fallback: there is no safe
  place to run other than an isolated tree, so failing to make one is a
  refusal, not a reason to use the shared one."
  [cfg role-id source]
  (let [root (:worktree-root cfg)]
    (cond
      (str/blank? (str root)) {:ok? false :why :no-worktree-root}
      :else
      (let [remote (first (str/split-lines (:out (git! source ["remote"]))))]
        (if (str/blank? (str remote))
          {:ok? false :why :no-remote}
          (let [ls (git! source ["ls-remote" "--symref" remote "HEAD"])
                branch (when (:ok? ls)
                         (second (re-find #"ref:\s+refs/heads/(\S+)\s+HEAD" (:out ls))))]
            (if-not branch
              {:ok? false :why :no-default-branch :detail (:err ls)}
              (let [fetched (git! source ["fetch" remote branch])]
                (if-not (:ok? fetched)
                  {:ok? false :why :fetch-failed :detail (:err fetched)}
                  (let [wt-branch (dispatch/worktree-branch role-id (.now js/Date))
                        p (path/join root wt-branch)
                        added (git! source ["worktree" "add" "-b" wt-branch p "FETCH_HEAD"])]
                    (if-not (:ok? added)
                      {:ok? false :why :worktree-add-failed :detail (:err added)}
                      {:ok? true :path p :branch wt-branch
                       :source source :base branch})))))))))))

(defn- remove-worktree!
  "Give back the tree a finished run was using. A worktree left behind is a
  checkout of a branch nobody will merge, and thirty of them is how a repo
  stops being readable."
  [{:keys [awai/worktree awai/worktree-source awai/worktree-branch]}]
  (when (and worktree worktree-source)
    (git! worktree-source ["worktree" "remove" "--force" worktree])
    (when worktree-branch
      (git! worktree-source ["branch" "-D" worktree-branch]))
    worktree))

(defn- tamaki-detached!
  "Start a run without waiting for it.

  The tick is a 300 s LaunchAgent and an AgentRun outlives that easily, so
  blocking here would make the loop miss its own next tick. Detaching is why
  submit and start are two calls: the run id has to exist before we stop
  watching."
  [cfg argv]
  (let [bin (tamaki-bin cfg)]
    (if-not (and bin (fs/existsSync bin))
      {:ok? false :why :tamaki-not-found :detail bin}
      (let [child (cp/spawn bin (clj->js argv)
                            #js {:detached true :stdio "ignore"
                                 :cwd (path/dirname (path/dirname bin))})]
        (.unref child)
        {:ok? true :pid (.-pid child)}))))

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

(defn cmd-runs []
  (let [rs (runs)]
    (doseq [r (sort-by (comp str :agent.run/yakuwari) rs)]
      (println (str (:agent.run/id r) "  " (name (or (:agent.run/status r) :unknown))
                    "  " (:agent.run/yakuwari r)
                    "  runner " (name (or (:agent.run/runner r) :none)))))
    (println (str "-- " (count rs) " live run(s)"))))

(defn- refresh
  "Persisted runs with tamaki's statuses folded in, terminal ones pruned.

  Deciding from an unrefreshed `runs.edn` would count finished runs as
  occupying slots, so the fleet would report itself at WIP and dispatch
  nothing — a loop that jams itself by never looking.

  Asked one run at a time, and only for runs we already hold. Measured
  2026-08-07: the bare `tamaki status` listing is 5.2 MB / 20,804 lines and
  takes 3m04s to fold — longer than this loop's own 300 s tick, so polling it
  every cycle would leave ticks overlapping and truncated output looking like
  a shorter run list. `status <id>` is 1 KB / 1m26s. The cost is therefore
  bounded by `:global-wip` rather than by the size of tamaki's history, and
  the ordinary case — no live runs — costs nothing at all."
  [cfg on-record]
  (if (empty? on-record)
    {:runs [] :orphans [] :from :empty}
    (let [bin (tamaki-bin cfg)]
      (if-not (and bin (fs/existsSync bin))
        ;; Distinguished from 'tamaki does not know these runs': without this
        ;; a missing binary would mark every live run an orphan.
        {:runs on-record :orphans [] :from :stale
         :error {:why :tamaki-not-found :detail bin}}
        (let [rows (vec (keep (fn [r]
                                (let [res (tamaki! cfg ["status" (:agent.run/id r)])]
                                  (when (:ok? res) (:value res))))
                              on-record))
              m (dispatch/merge-statuses on-record rows)
              live (dispatch/prune (:runs m))]
          {:runs live
           :orphans (:orphans m)
           ;; Kept, not counted: each one owns a worktree that has to be
           ;; handed back, and a count cannot say which directory.
           :terminal-runs (vec (filter dispatch/terminal? (:runs m)))
           :terminal (- (count (:runs m)) (count live))
           :from :tamaki})))))

(defn cmd-sync [apply?]
  (let [{:keys [fleet]} (load-registry)
        cfg (dispatch-config fleet)
        on-record (runs)
        {:keys [runs orphans terminal terminal-runs error from]} (refresh cfg on-record)]
    (when error
      (println (str "sync: tamaki unavailable — " (name (:why error))
                    (when (:detail error) (str ": " (:detail error))))))
    (println (str "sync: " (count on-record) " on record → " (count runs)
                  " live" (when terminal (str ", " terminal " terminal pruned"))))
    (doseq [r terminal-runs]
      (println (str "  DONE " (:agent.run/id r) " " (name (:agent.run/status r))
                    "  " (:agent.run/yakuwari r)
                    (when (:awai/worktree r)
                      (str "\n      tree " (:awai/worktree r)
                           (if apply? " — released" " — would release"))))))
    (doseq [o orphans]
      (println (str "  ORPHAN " (:agent.run/id o) " — tamaki does not know this run")))
    (doseq [s (dispatch/stuck cfg runs (.now js/Date))]
      (println (str "  STUCK " (:agent.run/id s) " (" (:agent.run/yakuwari s) ") queued "
                    (Math/round (/ (:queued-ms s) 1000)) "s — submitted but never leased")))
    (cond
      (= :stale from) (die 1)
      apply? (do (doseq [r terminal-runs] (remove-worktree! r))
                 (write-runs! runs)
                 (println "  written → journal/runs.edn"))
      :else (println "  dry-run: nothing written, no tree released (--apply to record)"))))

(defn- perform-submit!
  "Submit one run, then start it. Returns the record to persist, or nil.

  A submitted run that never starts holds its slot forever — `stale-run?`
  only reaps `:leased` runs — so a failed start is rolled back by cancelling
  the run we just created. Registering work nobody will do is worse than not
  registering it: the first is invisible, the second is a log line."
  [cfg s]
  (if-let [skip (cond
                  (:skip s) (name (:skip s))
                  ;; `project-path` builds a path; only the filesystem knows
                  ;; whether it is a checkout. tamaki's local mode would start
                  ;; an agent in a directory that does not exist, and a west
                  ;; project is absent until `west update` fetches it.
                  (not (fs/existsSync (:project s))) "project-not-checked-out")]
    (do (println (str "  SKIP " (:role/id s) " — " skip
                      (when (:project s) (str " (" (:project s) ")"))))
        nil)
    (let [wt (prepare-worktree! cfg (:role/id s) (:project s))]
      (if-not (:ok? wt)
        (do (println (str "  SKIP " (:role/id s) " — " (name (:why wt))
                          (when (:detail wt) (str ": " (:detail wt)))))
            nil)
        (let [s (dispatch/retarget s (:path wt))
              res (tamaki! cfg (:argv s))]
          (if-not (:ok? res)
            (do (println (str "  FAIL submit " (:role/id s) " — " (name (:why res))
                              (when (:detail res) (str ": " (:detail res)))))
                (remove-worktree! {:awai/worktree (:path wt)
                                   :awai/worktree-source (:source wt)
                                   :awai/worktree-branch (:branch wt)})
                nil)
            (let [run (:value res)
                  id (:agent.run/id run)
                  started (tamaki-detached! cfg (dispatch/start-argv id))]
              (if-not (:ok? started)
                (do (println (str "  FAIL start " id " — " (name (:why started))
                                  " — cancelling so it does not hold a slot"))
                    (tamaki! cfg (dispatch/cancel-argv {:run/id id}))
                    (remove-worktree! {:awai/worktree (:path wt)
                                       :awai/worktree-source (:source wt)
                                       :awai/worktree-branch (:branch wt)})
                    nil)
                (do (println (str "  RUN " id "  " (:role/id s)
                                  "  runner " (name (:runner s))
                                  "  pid " (:pid started)
                                  "\n      tree " (:path wt) " (" (:base wt) ")"))
                    (assoc (dispatch/link run s)
                           :awai/worktree (:path wt)
                           :awai/worktree-source (:source wt)
                           :awai/worktree-branch (:branch wt)))))))))))

(defn cmd-dispatch [apply?]
  (let [{:keys [fleet roles]} (load-registry)
        cfg (dispatch-config fleet)
        on-record (runs)
        {:keys [runs error]} (refresh cfg on-record)
        t (loop'/tick {:fleet fleet :roles roles :runs runs
                       :now-ms (.now js/Date)
                       :last-dispatch (last-dispatch)})
        d (:decision t)
        p (dispatch/plan cfg (:effects t) roles runs)]
    (when-not (:workspace cfg)
      (println "dispatch: no workspace found — set AWAI_WORKSPACE")
      (die 1))
    (when error
      (println (str "dispatch: refusing to act on stale run state — "
                    (name (:why error))
                    (when (:detail error) (str ": " (:detail error)))))
      (die 1))
    (println (str "active " (:active d) "/" (:global-wip d)
                  "  free " (:free d)
                  "  dispatch " (mapv :role/id (:dispatch d))
                  "  cancels " (count (:cancels p))))
    (if-not apply?
      (do (doseq [s (:submits p)]
            (if (:skip s)
              (println (str "  would SKIP " (:role/id s) " — " (name (:skip s))))
              (println (str "  would RUN " (:role/id s) " via " (name (:runner s))
                            "\n    tamaki " (str/join " " (map pr-str (:argv s)))))))
          (doseq [c (:cancels p)]
            (println (str "  would CANCEL " (second c))))
          (println "dry-run: nothing submitted, nothing recorded (--apply to act)"))
      (let [ready (when (seq (:submits p)) (runtime-ready cfg))
            _ (when (and ready (not (:ready? ready)))
                (println (str "dispatch: refusing to submit — " (name (:why ready))
                              (when (:detail ready) (str ": " (:detail ready)))
                              "\n  a run submitted into a runtime that cannot start it"
                              " holds its slot until a human notices."))
                (die 1))
            _ (doseq [c (:cancels p)]
                (let [r (tamaki! cfg c)]
                  (println (str "  CANCEL " (second c)
                                (when-not (:ok? r) (str " — FAILED: " (name (:why r))))))))
            cancelled (set (map second (:cancels p)))
            kept (vec (remove #(contains? cancelled (:agent.run/id %)) runs))
            fresh (vec (keep #(perform-submit! cfg %) (:submits p)))]
        (write-runs! (into kept fresh))
        (fs/appendFileSync (at "journal" "current.edn")
                           (str (pr-str (assoc (:evidence t)
                                               :tick/started (count fresh)
                                               :tick/cancelled (count cancelled)))
                                "\n"))
        (println (str "recorded → journal/runs.edn (" (count (into kept fresh))
                      " live), journal/current.edn"))))))

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
      "runs" (cmd-runs)
      "sync" (cmd-sync apply?)
      "dispatch" (cmd-dispatch apply?)
      (do (println "usage: awai <check|roles|ceiling|identities|project|tick|runs|sync|dispatch>")
          (die 2)))))

;; nbb passes script arguments as *command-line-args*; js/process.argv[1] is
;; nbb's own entrypoint, not this file.
(apply -main *command-line-args*)
