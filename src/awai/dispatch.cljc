(ns awai.dispatch
  "The acting half: `:spawn` and `:reap` effects as tamaki invocations.

  `awai.loop` decides which role gets the next slot and returns effects as
  data; until this namespace existed nothing turned those effects into a
  bounded execution, so `journal/runs.edn` never appeared and every role
  projected as `:starved` — correctly, because nothing was executing.

  Pure, like the rest of `src/`. Every function here returns argv vectors and
  records; `bin/awai.cljs` runs the processes and writes the files. That split
  is what makes a dispatch decision reviewable without a tamaki installed.

  ## Why tamaki rather than a runner of our own

  `etzhayyim/tamaki` already owns durable agent execution: one AgentRun
  identity, an append-only event history, the state machine, and the worktree
  per runner. `yakuwari.reconcile`'s own docstring says it was extracted from
  `kotoba.tamaki.actor`, so the two halves are the same lineage. Rebuilding
  the executing half here would be a second implementation of the layer this
  repo is `:must-not [:own-domain-scoring-truth]` about.

  ## The decisions worth knowing

  **Submit and start are two calls, not `submit --execute`.** tamaki's
  `--execute` runs the agent synchronously, so a 300 s LaunchAgent tick would
  block for the length of the run and miss its own next tick. Two calls give
  us the run id before execution begins, which is also the only moment we can
  record the role linkage.

  **Nothing is registered that is not also started.** A `:queued` run occupies
  a slot, and `reconcile/stale-run?`'s default policy only reaps `:leased`
  ones — so a run submitted and never executed jams its slot permanently, and
  six of them jam the fleet. There is no half-state here on purpose: either
  the work really starts, or nothing is submitted.

  **The runner is picked by index, not at random.** A tick has to be
  explainable from its journal entry, and `rand-nth` makes the same inputs
  produce a different dispatch every time it is re-read.

  **The policy travels twice: as prose in the goal, as data in the record.**
  tamaki's AgentRun has no field for a yakuwari capability policy, so the goal
  text is the only channel that reaches the model actually doing the work. The
  structured policy is persisted alongside because prose is not auditable —
  the text is for the runner, the map is for the reviewer.

  **Terminal runs are pruned.** `journal/runs.edn` answers 'what occupies a
  slot', and tamaki's event store is the history. Keeping succeeded runs here
  would grow the file without changing a single decision."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; configuration
;; ---------------------------------------------------------------------------

(def default-config
  "Shape only. The machine's location is not in `fleet.edn` on purpose: a
  checkout path is not fleet policy, and committing one makes the file wrong
  on every other machine. `bin/awai.cljs` resolves `:workspace` from the
  environment and merges it over this."
  {:tamaki "orgs/etzhayyim/tamaki/bin/tamaki"
   :project-root "orgs"
   :mode :local
   ;; Where per-run worktrees are created. Absent means dispatch refuses to
   ;; start anything: there is no safe fallback, because the fallback would be
   ;; the shared checkout. See `retarget`.
   :worktree-root nil
   ;; A run that is still :queued this long after submission was never leased
   ;; — reported rather than reaped, because the fix is 'find out why tamaki
   ;; did not start it', not 'make the symptom go away'.
   :queued-grace-ms 900000})

(defn config
  "Dispatch configuration: defaults, narrowed by `fleet.edn`, then by the
  caller's overrides (which carry the machine-specific paths)."
  [fleet & [overrides]]
  (merge default-config (:awai.fleet/dispatch fleet) overrides))

;; ---------------------------------------------------------------------------
;; runner selection
;; ---------------------------------------------------------------------------

(defn runner-pool
  "The weighted pool expanded to one entry per unit of weight.

  Expanding rather than sampling keeps the choice a pure function of an
  integer, so a dispatch can be replayed from the journal. A weight of 2
  against 1 means two slots against one, which is the same thing sampling
  would mean on average and the same thing on every individual tick."
  [runners]
  (vec (mapcat (fn [{:keys [runner weight]}]
                 (repeat (max 1 (or weight 1)) runner))
               (remove (comp nil? :runner) runners))))

(defn runner-for
  "Pick the `n`th runner from a role's pool, or nil if the pool is empty.

  `n` is how many runs this role already has on record. Rotating on observed
  state rather than a counter in memory means two processes reading the same
  `runs.edn` pick the same runner, and a dispatch stays explainable after the
  fact."
  [runners n]
  (let [pool (runner-pool runners)]
    (when (seq pool)
      (nth pool (mod (max 0 n) (count pool))))))

(defn proposal-disposition
  "What terminal cleanup may safely do with a coding worktree.

  Successful output is never force-deleted: committed work becomes a branch
  proposal, while an uncommitted patch retains its tree for review."
  [status dirty? changed?]
  (cond
    dirty? :retained-dirty-worktree
    (and (= :succeeded status) changed?) :preserved-branch
    (= :succeeded status) :released-no-change
    :else :released-failure))

;; ---------------------------------------------------------------------------
;; the goal
;; ---------------------------------------------------------------------------

(defn- policy-lines
  "The constraints a runner must be told about, worst first.

  Only the non-autonomous decisions are listed. A grant needs no sentence —
  the objective already implies it — while a bound that is not stated is a
  bound the model will discover by violating it."
  [policy]
  (let [by (fn [d] (sort (map name (keep (fn [[cap v]] (when (= d v) cap)) policy))))]
    (->> [[:blocked (by :blocked) "must not"]
          [:voice-required (by :voice-required) "needs the principal's voice before"]
          [:approval-required (by :approval-required) "needs human approval before"]]
         (keep (fn [[_ caps phrase]]
                 (when (seq caps)
                   (str "- This run " phrase ": " (str/join ", " caps) "."))))
         vec)))

(defn goal-for
  "The run's stated goal: the role's standing objective, plus the bound it is
  being executed under.

  The objective alone would be wrong to hand a bounded execution — 'convert a
  named engineering organisation' is a mandate, not a task, and an agent given
  a mandate as a goal produces work nobody asked for. Naming the tick and the
  policy turns it into something a reviewer can judge finished or not."
  [{:keys [role/id role/business goal policy]}]
  (str "[" (name (or business :awai)) "] one bounded execution for the "
       (str id) " role.\n\n"
       ;; Role objectives are authored as indented multi-line EDN strings, so
       ;; the source indentation rides along into the goal unless it is
       ;; collapsed here. It reaches a model as a prompt, not as a file.
       "Standing objective: " (str/trim (str/replace (str goal) #"\s+" " ")) "\n\n"
       "Bounds for this run:\n"
       (str/join "\n"
                 (concat
                  ["- Advance the objective by one reviewable step, then stop."
                   "- Act only within this role's own business."]
                  (policy-lines policy)))))

;; ---------------------------------------------------------------------------
;; invocations
;; ---------------------------------------------------------------------------

(defn project-path
  "Filesystem path for a role's `:yakuwari/project` (`\"<org>/<repo>\"`).

  Returns nil for anything that is not org/repo shaped rather than guessing —
  a wrong `--project` would run an agent against the wrong repository, which
  is the one error here that edits somebody else's files."
  [{:keys [workspace project-root]} project]
  (let [parts (str/split (str project) #"/")]
    (when (and workspace (= 2 (count parts)) (every? (complement str/blank?) parts))
      (str/join "/" (concat [workspace] (when project-root [project-root]) parts)))))

(defn submit-argv
  "`tamaki submit` for one spawn effect, or nil with a reason if it cannot be
  built. Returns data so `bin/awai.cljs` performs a decision it did not make."
  [cfg {:keys [role/id role/business runners] :as effect} project n]
  (let [path (project-path cfg project)
        ;; Fleet runners are the admitted, live-qualified execution substrate.
        ;; Role runner lists are preferences used only when no fleet ceiling is
        ;; supplied; otherwise a stale role preference could bypass readiness.
        admitted (or (seq (:runners cfg)) runners)
        runner (runner-for admitted n)]
    (cond
      (nil? path) {:skip :unresolvable-project :role/id id :project project}
      (nil? runner) {:skip :no-runner :role/id id}
      :else
      {:role/id id
       :role/business business
       :runner runner
       :project path
       ;; Carried so `link` can persist the ceilinged policy without reading
       ;; the role file again. `awai.loop/act` already applied the fleet
       ;; ceiling; re-deriving it here would let a widened role file escape it.
       :policy (:policy effect)
       :argv (cond-> ["submit" (goal-for effect)
                      "--project" path
                      "--mode" (name (:mode cfg))
                      "--runner" (name runner)]
               (:requires cfg) (conj "--requires" (:requires cfg)))})))

(defn retarget
  "Re-point a built submit at an isolated working tree.

  `plan` resolves the checkout the role declares, because that is the only
  thing a role file knows. The tree an agent may actually edit is decided at
  dispatch time, when the worktree exists — and it must never be the shared
  west checkout: tamaki's local mode runs `kotoba-code <goal> <project>`
  directly in that directory, and a parallel session's `git checkout` there
  silently reverts uncommitted work (CLAUDE.md, worktree-per-agent).

  The value is rewritten rather than the vector rebuilt so the goal text —
  which is most of the argv — is not derived twice."
  [submit project]
  (let [argv (:argv submit)
        i (some (fn [[i v]] (when (= "--project" v) (inc i)))
                (map-indexed vector argv))]
    (cond-> (assoc submit :source-project (:project submit) :project project)
      i (assoc :argv (assoc argv i project)))))

(defn worktree-branch
  "Branch name for one run's isolated tree. Namespaced by the fleet so a
  human reading `git branch` in a business repo can tell which loop made it."
  [role-id stamp]
  (str "awai/" (str/replace (subs (str role-id) 1) "/" "-") "-" stamp))

(defn start-argv
  "`tamaki run <id>`. Separate from submit so the tick does not block for the
  length of the execution."
  [run-id]
  ["run" (str run-id)])

(defn cancel-argv
  "`tamaki cancel <id>` for a reap effect. A stale lease is cancelled rather
  than forgotten: dropping it from `runs.edn` alone would free the slot here
  while tamaki still believed the run was live."
  [{:keys [run/id]}]
  ["cancel" (str id) "--reason" "stale-lease reaped by awai yakuwari fleet"])

;; ---------------------------------------------------------------------------
;; records
;; ---------------------------------------------------------------------------

(defn link
  "The record `journal/runs.edn` keeps for a submitted run.

  `:agent.run/yakuwari` is what `reconcile/runs-for` joins on, and tamaki's
  `agent-run` does not set it — the run it prints knows its goal and its
  runner but not which role asked for it. Without this key every run reads as
  belonging to no role, and the plan that spawned it would spawn another on
  the next tick."
  [run {:keys [role/id role/business policy]}]
  (assoc (select-keys run [:agent.run/id :agent.run/status :agent.run/mode
                           :agent.run/runner :agent.run/model :agent.run/goal
                           :agent.run/created-at :agent.run/updated-at
                           :agent.run/budget])
         :agent.run/yakuwari id
         :role/business business
         :yakuwari/policy policy))

(defn terminal?
  [run]
  (contains? #{:succeeded :failed :rejected :cancelled} (:agent.run/status run)))

(defn merge-statuses
  "Refresh persisted runs from a `tamaki status` listing.

  Returns `{:runs ... :orphans ...}`. A persisted run tamaki no longer knows
  about is reported, never silently dropped: it occupies a slot in every
  future tick, and a fleet that quietly forgets its own runs is one where
  `active` stops meaning anything."
  [runs status-rows]
  (let [by-id (into {} (map (juxt :agent.run/id identity) status-rows))]
    {:runs (mapv (fn [r]
                   (if-let [s (get by-id (:agent.run/id r))]
                     (merge r (select-keys s [:agent.run/status :agent.run/updated-at
                                              :agent.run/runner :agent.run/model]))
                     r))
                 runs)
     :orphans (vec (remove #(contains? by-id (:agent.run/id %)) runs))}))

(defn prune
  "Drop terminal runs. They no longer occupy a slot, and tamaki's event store
  is the history — keeping them here would grow the file without changing a
  decision."
  [runs]
  (vec (remove terminal? runs)))

(defn stuck
  "Runs still `:queued` longer than the grace period.

  These are the shape of failure this design is most exposed to: submission
  succeeded, starting did not, and the slot is held by something no runner
  ever picked up. Named rather than reaped — reaping would hide a tamaki that
  is refusing to start runs."
  [cfg runs now-ms]
  (let [grace (or (:queued-grace-ms cfg) 0)]
    (vec (for [r runs
               :when (and (= :queued (:agent.run/status r))
                          now-ms
                          (>= (- now-ms (or (:agent.run/updated-at r)
                                            (:agent.run/created-at r) 0))
                              grace))]
           {:agent.run/id (:agent.run/id r)
            :agent.run/yakuwari (:agent.run/yakuwari r)
            :queued-ms (- now-ms (or (:agent.run/updated-at r)
                                     (:agent.run/created-at r) 0))}))))

;; ---------------------------------------------------------------------------
;; the plan
;; ---------------------------------------------------------------------------

(defn plan
  "Every invocation this tick's effects call for, as data.

  `runs` supplies the per-role rotation count, so the runner a role gets is a
  function of what it already has rather than of when the tick happened."
  [cfg effects roles runs]
  (let [project-of (into {} (map (juxt :yakuwari/id :yakuwari/project) roles))
        counts (frequencies (keep :agent.run/yakuwari runs))]
    {:cancels (mapv cancel-argv (:reap effects))
     :submits (vec (second
                    (reduce (fn [[seen acc] effect]
                              (let [id (:role/id effect)
                                    n (+ (get counts id 0) (get seen id 0))]
                                [(update seen id (fnil inc 0))
                                 (conj acc (submit-argv cfg effect
                                                        (get project-of id) n))]))
                            [{} []]
                            (:spawn effects))))}))
