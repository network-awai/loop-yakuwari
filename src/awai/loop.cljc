(ns awai.loop
  "The continuous orchestrator: observe -> evaluate -> decide -> act ->
  record-evidence.

  Those five verbs are the `loop-*` contract in the workspace authority
  (`manifest/repository-rules.edn`, ADR-2607299000), and the same file's
  `:must-not [:own-domain-scoring-truth]` is why nothing here computes
  capacity: `yakuwari.reconcile/plan` decides how many runs a role should
  have, `awai.registry` decides what a role may do, and this namespace only
  chooses which role gets the next slot when more want one than exist.

  Pure. `now-ms` and observations arrive from the caller and `act` returns a
  plan rather than performing it, so the deciding half is testable without a
  fleet and the performing half is a script (`bin/awai.cljs`).

  ## Why a global slot budget rather than per-business

  Six businesses each given a comfortable ceiling sum to a number nobody
  chose, and the real constraint — runner subscriptions on one machine — binds
  globally. `fleet.edn` puts `:global-wip` at the level where it actually
  binds, as tamaki's `etzhayyim-fleet.edn` does. The cost is that six
  businesses compete, which is exactly why scoring below is explicit rather
  than first-come."
  (:require [yakuwari.reconcile :as reconcile]
            [awai.registry :as registry]))

;; ---------------------------------------------------------------------------
;; observe
;; ---------------------------------------------------------------------------

(defn observe
  "One reading of the world: each role's plan, plus what is live fleet-wide.

  Roles whose spec `reconcile/plan` refuses are carried with `:plan nil` and
  an `:error`, never dropped. A role that vanishes from the observation
  because its file is malformed reads as a role with nothing to do, which is
  the one reading that guarantees nobody fixes it."
  [{:keys [roles runs now-ms]}]
  (let [runs (vec runs)]
    {:observed-at now-ms
     :active (count (filterv #(contains? reconcile/active-statuses
                                         (:agent.run/status %)) runs))
     :roles (mapv (fn [r]
                    (let [plan (try (reconcile/plan r runs now-ms)
                                    (catch #?(:clj Exception :cljs :default) e
                                      {::error (or (ex-message e) "invalid spec")}))]
                      {:role/id (:yakuwari/id r)
                       :role/business (:yakuwari/business r)
                       :role/outward? (boolean (:yakuwari/outward? r))
                       :plan (when-not (::error plan) plan)
                       :error (::error plan)}))
                  roles)}))

;; ---------------------------------------------------------------------------
;; evaluate
;; ---------------------------------------------------------------------------

(defn score
  "How much this role needs the next slot.

  Weights come from `fleet.edn` so the judgement is reviewable data rather
  than a constant in code. A role blocked on a human outranks one merely idle
  because the blocked one already consumed work that is wasted if it times
  out — the same reasoning `yakuwari.reconcile` uses when it lets held runs
  raise capacity instead of letting the backlog and the HIL queue starve each
  other.

  A role with nothing to spawn scores 0 regardless of weights: wanting no
  capacity is not a claim on any."
  [fleet observation]
  (let [w (or (:awai.fleet/weights fleet) {})
        {:keys [plan]} observation]
    (if (or (nil? plan) (not (pos? (or (:spawn plan) 0))))
      0.0
      (+ (* (or (:blocked-on-human w) 0) (or (:blocked plan) 0))
         (* (or (:queue-depth w) 0) (or (:queued plan) 0))
         (* (or (:staleness w) 0) (count (or (:reap plan) [])))
         (* (or (:outward w) 0) (if (:role/outward? observation) 1 0))
         ;; A role wanting capacity at all outranks one that does not, even
         ;; with every other signal at zero — otherwise a freshly idle role
         ;; never starts.
         0.01))))

(defn evaluate
  "Score every observed role, worst-first. Roles that errored sort last with
  score 0 but stay in the list: they need a human, not a slot."
  [fleet observation]
  (let [scored (mapv (fn [o] (assoc o :score (score fleet o)))
                     (:roles observation))]
    (assoc observation
           :roles (vec (sort-by (juxt (comp - :score)
                                      (comp str :role/id))
                                scored)))))

;; ---------------------------------------------------------------------------
;; decide
;; ---------------------------------------------------------------------------

(defn decide
  "Which roles get a slot this tick, and why the rest did not.

  Two bounds, both from `fleet.edn`: `:global-wip` caps everything live at
  once, and `:dispatch-per-tick` caps how fast the fleet may accelerate. The
  second exists because the first alone lets one tick fill every free slot
  with whatever happened to score highest at that instant.

  `:last-dispatch` breaks ties by oldest, so an alphabetical prefix cannot
  monopolise capacity — `app-aozora` would otherwise always precede
  `nexus-x402`."
  [fleet evaluated & [{:keys [last-dispatch]}]]
  (let [res (or (:awai.fleet/resources fleet) {})
        wip (or (:global-wip res) 1)
        per-tick (or (:dispatch-per-tick res) 1)
        free (max 0 (- wip (:active evaluated 0)))
        allowance (min free per-tick)
        eligible (filterv #(and (pos? (:score %)) (nil? (:error %)))
                          (:roles evaluated))
        ordered (if (= :oldest-dispatch (:awai.fleet/tie-break fleet))
                  (vec (sort-by (juxt (comp - :score)
                                      #(get last-dispatch (:role/id %) 0))
                                eligible))
                  eligible)
        chosen (vec (take allowance ordered))
        chosen-ids (set (map :role/id chosen))]
    {:decided-at (:observed-at evaluated)
     :global-wip wip
     :active (:active evaluated 0)
     :free free
     :allowance allowance
     :dispatch chosen
     ;; Named reasons, because "nothing happened this tick" is the state an
     ;; operator most often needs explained. A loop that reports only what it
     ;; did is unreadable on the ticks where it did nothing.
     :withheld
     (vec (concat
           (for [r (:roles evaluated) :when (:error r)]
             {:role/id (:role/id r) :reason :invalid-spec :detail (:error r)})
           (for [r (:roles evaluated)
                 :when (and (nil? (:error r)) (zero? (:score r)))]
             {:role/id (:role/id r) :reason :wants-no-capacity})
           (for [r ordered :when (not (contains? chosen-ids (:role/id r)))]
             {:role/id (:role/id r)
              :reason (if (zero? free) :fleet-at-wip :tick-allowance-spent)})))}))

;; ---------------------------------------------------------------------------
;; act
;; ---------------------------------------------------------------------------

(defn act
  "The decision as effects to perform, still data.

  Reaping precedes spawning on purpose: a stale lease occupies a slot, so
  freeing it first can make a spawn fit that would otherwise have been
  withheld. `:spawn` carries the runner pool from the role and the fleet
  ceiling already applied, so whoever performs it cannot widen a grant by
  reading the role file directly."
  [fleet roles decision]
  (let [by-id (into {} (map (juxt :yakuwari/id identity) roles))]
    {:reap (vec (for [d (:dispatch decision)
                      run-id (:reap (:plan d))]
                  {:effect :reap :role/id (:role/id d) :run/id run-id}))
     :spawn (vec (for [d (:dispatch decision)
                       :let [r (get by-id (:role/id d))]
                       _ (range (or (:spawn (:plan d)) 0))]
                   {:effect :spawn
                    :role/id (:role/id d)
                    :role/business (:role/business d)
                    :goal (:yakuwari/objective r)
                    :runners (vec (:yakuwari/runners r))
                    :policy (registry/ceilinged-policy fleet r)}))}))

;; ---------------------------------------------------------------------------
;; record-evidence
;; ---------------------------------------------------------------------------

(defn record-evidence
  "One journal entry per tick, appended whether or not anything was
  dispatched.

  A loop that only records the ticks where it acted leaves no way to tell
  'nothing needed doing' apart from 'the loop stopped running' — and those
  have opposite fixes. `:withheld-summary` is counted rather than listed so a
  quiet tick stays one short line.

  No timestamp is generated here: `now-ms` comes from the caller, because a
  clock inside a pure function makes the journal unreproducible."
  [{:keys [decision effects tick-of now-ms]}]
  {:tick/at now-ms
   :tick/of tick-of
   :tick/active (:active decision)
   :tick/free (:free decision)
   :tick/dispatched (mapv :role/id (:dispatch decision))
   :tick/spawned (count (:spawn effects))
   :tick/reaped (count (:reap effects))
   :tick/withheld-summary (frequencies (map :reason (:withheld decision)))
   ;; Invalid specs are named in full: they are the entries a human must act
   ;; on, and a count alone would not say which role to fix.
   :tick/invalid (vec (for [w (:withheld decision)
                            :when (= :invalid-spec (:reason w))]
                        (:role/id w)))})

(defn tick
  "One full cycle, as data. The caller performs `:effects` and appends
  `:evidence`."
  [{:keys [fleet roles runs now-ms last-dispatch]}]
  (let [obs (observe {:roles roles :runs runs :now-ms now-ms})
        evaluated (evaluate fleet obs)
        decision (decide fleet evaluated {:last-dispatch last-dispatch})
        effects (act fleet roles decision)]
    {:observation obs
     :evaluation evaluated
     :decision decision
     :effects effects
     :evidence (record-evidence {:decision decision :effects effects
                                 :now-ms now-ms :tick-of :awai/yakuwari-fleet})}))
