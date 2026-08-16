(ns awai.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [yakuwari.spec :as spec]
            [awai.registry :as registry]
            [awai.loop :as loop']
            [awai.dispatch :as dispatch]
            [clojure.string :as str]))

(def t0 1785000000000)

(def fleet
  {:awai.fleet/resources {:global-wip 3 :dispatch-per-tick 1}
   :awai.fleet/weights {:blocked-on-human 3.0 :queue-depth 2.0
                        :staleness 1.0 :outward 0.5}
   :awai.fleet/tie-break :oldest-dispatch
   :awai.fleet/capability-ceiling {:spend.commit :approval-required
                                   :account.create :blocked
                                   :content.publish :voice-required
                                   :subject.erase :autonomous}})

(defn role-of [id business & {:as extra}]
  (merge {:yakuwari/id id
          :yakuwari/business business
          :yakuwari/project (str "network-awai/" (name business))
          :yakuwari/objective (str "do the " (name id) " job")
          :yakuwari/scale {:min 0 :desired 1 :max 3}
          :yakuwari/runners [{:runner :claude :weight 1}]
          :yakuwari/capabilities [{:capability :issue.create :decision :autonomous}]}
         extra))

(defn run-of [id role status & [at]]
  {:agent.run/id id :agent.run/yakuwari role :agent.run/status status
   :agent.run/created-at (or at t0) :agent.run/updated-at (or at t0)})

;; ---------------------------------------------------------------------------
;; The fleet ceiling can only narrow
;; ---------------------------------------------------------------------------

(deftest the-ceiling-narrows-a-role-that-claims-more
  (let [r (role-of :x402/marketer :nexus-x402
                   :yakuwari/capabilities
                   [{:capability :content.publish :decision :autonomous}])]
    (testing "the role says :autonomous; the fleet says :voice-required"
      (is (= :autonomous (spec/decide r :content.publish)))
      (is (= :voice-required (registry/decide fleet r :content.publish))))))

(deftest the-ceiling-never-widens-a-role-that-claims-less
  (let [r (role-of :babiniku/marketer :net-babiniku
                   :yakuwari/capabilities
                   [{:capability :content.publish :decision :approval-required}
                    {:capability :subject.erase :decision :blocked}])]
    (testing "a role stricter than the ceiling keeps its own answer"
      (is (= :approval-required (registry/decide fleet r :content.publish))))
    (testing "even where the ceiling is :autonomous, it cannot grant"
      (is (= :blocked (registry/decide fleet r :subject.erase))))))

(deftest the-ceiling-is-a-bound-not-a-whitelist
  (testing "a capability the ceiling never mentions is left as written"
    (let [r (role-of :isekai/engineer :network-isekai
                     :yakuwari/capabilities
                     [{:capability :patch.create :decision :autonomous}])]
      (is (= :autonomous (registry/decide fleet r :patch.create)))))
  (testing "and an unlisted capability still fails closed"
    (is (= :blocked (registry/decide fleet (role-of :a :b) :whatever.unlisted)))))

(deftest narrowings-are-reported-so-a-reviewer-sees-the-difference
  (let [r (role-of :m :nexus-x402
                   :yakuwari/capabilities
                   [{:capability :content.publish :decision :autonomous}
                    {:capability :issue.create :decision :autonomous}])
        n (registry/ceiling-narrowings fleet r)]
    (is (= 1 (count n)))
    (is (= {:capability :content.publish :role-says :autonomous
            :fleet-says :voice-required}
           (first n)))))

;; ---------------------------------------------------------------------------
;; Cross-file drift
;; ---------------------------------------------------------------------------

(def businesses
  {:awai.businesses/outward-roles #{:director :sales :supporter}
   :awai.businesses/businesses
   [{:business/id :network-isekai :business/name "network isekai"
     :business/domain "isekai.network"
     :business/roles [:director :engineer]
     :business/roles-absent [{:role :sales :why "no pipeline"}]}]})

(defn check [roles]
  (registry/validate-fleet {:fleet fleet :businesses businesses :roles roles}))

(deftest a-consistent-registry-passes
  (let [r (check [(role-of :network-isekai/director :network-isekai
                           :yakuwari/outward? true
                           :yakuwari/identity "network-awai/person-isekai-director"
                           :yakuwari/mailbox "director@isekai.network")
                  (role-of :network-isekai/engineer :network-isekai)])]
    (is (:ok? r) (pr-str (:problems r)))
    (is (= 2 (:roles (:counts r))))
    (is (= 1 (:outward (:counts r))))))

(deftest a-role-naming-an-undeclared-business-is-caught
  (is (some #(= :role-unknown-business (:problem %))
            (:problems (check [(role-of :typo/director :no-such-business)])))))

(deftest a-role-the-business-does-not-list-is-caught
  (testing "the business exists but never claimed a designer"
    (is (some #(= :role-not-in-business (:problem %))
              (:problems (check [(role-of :network-isekai/designer :network-isekai)]))))))

(deftest a-business-promising-a-role-with-no-file-is-caught
  (testing "businesses.edn lists :engineer; only :director has a file"
    (let [r (check [(role-of :network-isekai/director :network-isekai
                             :yakuwari/outward? true
                             :yakuwari/identity "x" :yakuwari/mailbox "y")])]
      (is (some #(and (= :business-role-missing (:problem %))
                      (= :engineer (:kind %)))
                (:problems r))))))

(deftest a-documented-absence-that-is-not-absent-is-caught
  (testing "an explanation for why a role does not exist, next to the role, is worse than none"
    (is (some #(= :absent-role-present (:problem %))
              (:problems (check [(role-of :network-isekai/sales :network-isekai)]))))))

(deftest two-roles-sharing-an-id-are-caught
  (testing "reconcile attributes runs by id, so duplicates would pool each other's executions"
    (is (some #(= :duplicate-role-id (:problem %))
              (:problems (check [(role-of :network-isekai/director :network-isekai)
                                 (role-of :network-isekai/director :network-isekai)]))))))

(deftest an-outward-role-with-no-identity-or-mailbox-is-caught
  (let [ps (:problems (check [(role-of :network-isekai/director :network-isekai
                                       :yakuwari/outward? true)]))]
    (is (some #(= :outward-without-identity (:problem %)) ps))
    (is (some #(= :outward-without-mailbox (:problem %)) ps))))

(deftest an-identity-on-a-non-outward-role-is-caught
  (testing "a person-* repo no surface will mark reachable"
    (is (some #(= :identity-without-outward (:problem %))
              (:problems (check [(role-of :network-isekai/engineer :network-isekai
                                          :yakuwari/identity "network-awai/person-x")]))))))

(deftest a-role-outside-the-fleets-outward-set-cannot-claim-outward
  (is (some #(= :non-outward-role-outward (:problem %))
            (:problems (check [(role-of :network-isekai/engineer :network-isekai
                                        :yakuwari/outward? true
                                        :yakuwari/identity "x"
                                        :yakuwari/mailbox "y")])))))

(deftest an-invalid-spec-is-reported-alongside-the-drift-checks
  (let [ps (:problems (check [{:yakuwari/id :network-isekai/director
                               :yakuwari/business :network-isekai}]))]
    (is (some #(= :invalid-spec (:problem %)) ps))))

(deftest every-problem-is-reported-at-once
  (testing "one error per round trip turns an N-file registry into an N-step fix"
    (let [ps (:problems (check [(role-of :nope/director :no-such-business)
                                (role-of :network-isekai/sales :network-isekai)]))]
      (is (>= (count ps) 3))
      (is (contains? (set (map :problem ps)) :role-unknown-business))
      (is (contains? (set (map :problem ps)) :absent-role-present)))))

(deftest workforce-templates-fill-only-declared-missing-roles
  (let [businesses {:awai.businesses/businesses
                    [{:business/id :x :business/name "X" :business/domain "x.test"
                      :business/repo "network-awai/x" :business/what "does x"
                      :business/roles [:director :qa]}]}
        workforce {:awai.workforce/templates
                   {:qa {:bot/role :qa :bot/name "QA" :bot/cadence-minutes 60
                         :yakuwari/objective "falsify it"
                         :yakuwari/scale {:min 0 :desired 1 :max 1}
                         :yakuwari/runners [{:runner :codex :weight 1}]
                         :yakuwari/capabilities
                         [{:capability :test.run :decision :autonomous}]}
                    :engineer {:yakuwari/objective "should not appear"}}}
        authored [(role-of :x/director :x)]
        completed (registry/complete-roles businesses workforce authored)
        by-id (into {} (map (juxt :yakuwari/id identity)) completed)]
    (is (= #{:x/director :x/qa} (set (keys by-id))))
    (is (= :awai.workforce/template (:yakuwari/generated-from (:x/qa by-id))))
    (is (= "do the director job" (:yakuwari/objective (:x/director by-id)))
        "an authored role wins over a template")))

(deftest workforce-projection-carries-policy-but-does-not-call-it-a-grant
  (let [businesses {:awai.businesses/businesses
                    [{:business/id :x :business/name "X" :business/domain "x.test"
                      :business/repo "network-awai/x" :business/what "does x"
                      :business/roles [:qa]}]}
        workforce {:awai.workforce/templates
                   {:qa {:bot/role :qa :bot/name "QA" :bot/cadence-minutes 60}}}
        role (role-of :x/qa :x
                      :yakuwari/capabilities
                      [{:capability :deploy.production :decision :blocked}])
        result (registry/workforce-bots
                {:fleet fleet :businesses businesses :workforce workforce
                 :roles [role]})
        projected (first (:roles result))]
    (is (= "network.awai.workforce-bots.v1" (:schema result)))
    (is (= "x/qa" (:key projected)))
    (is (= :qa (get-in projected [:role :job])))
    (is (= :blocked (get-in projected [:capabilities 0 :decision])))
    (is (nil? (:tools projected))
        "semantic capability policy must not become a Cloud Itonami tool grant")))

;; ---------------------------------------------------------------------------
;; The loop
;; ---------------------------------------------------------------------------

(deftest a-role-that-wants-no-capacity-scores-zero
  (let [r (role-of :a/idle :network-isekai :yakuwari/scale {:min 0 :desired 0 :max 1})
        obs (loop'/observe {:roles [r] :runs [] :now-ms t0})]
    (is (= 0.0 (loop'/score fleet (first (:roles obs)))))))

(deftest blocked-on-a-human-outranks-merely-idle
  (testing "the :blocker-count threshold is what makes held runs raise capacity"
    (let [blocked (role-of :a/blocked :network-isekai
                           :yakuwari/scale {:min 0 :desired 1 :max 3
                                            :scale-up-on {:blocker-count 1}})
          idle (role-of :a/idle :network-isekai)
          runs [(run-of "h" :a/blocked :held)]
          obs (loop'/observe {:roles [blocked idle] :runs runs :now-ms t0})
          by-id (into {} (map (juxt :role/id #(loop'/score fleet %)) (:roles obs)))]
      (is (> (:a/blocked by-id) (:a/idle by-id))))))

(deftest a-role-at-capacity-wants-no-slot-even-while-blocked
  (testing "without a :blocker-count threshold a held run occupies the slot it
            already has — that role needs a human, not more capacity, and
            scoring it above an idle role would spend a slot on nothing"
    (let [blocked (role-of :a/blocked :network-isekai
                           :yakuwari/scale {:min 0 :desired 1 :max 3})
          obs (loop'/observe {:roles [blocked] :runs [(run-of "h" :a/blocked :held)]
                              :now-ms t0})]
      (is (= 0 (:spawn (:plan (first (:roles obs))))))
      (is (= 0.0 (loop'/score fleet (first (:roles obs))))))))

(deftest an-invalid-role-is-observed-with-an-error-not-dropped
  (let [broken {:yakuwari/id :a/ghost :yakuwari/business :network-isekai}
        obs (loop'/observe {:roles [broken] :runs [] :now-ms t0})]
    (testing "a role that vanishes reads as a role with nothing to do"
      (is (= 1 (count (:roles obs))))
      (is (some? (:error (first (:roles obs)))))
      (is (nil? (:plan (first (:roles obs))))))))

(deftest dispatch-respects-global-wip
  (let [roles [(role-of :a/one :network-isekai) (role-of :a/two :network-isekai)]
        ;; 3 already live == :global-wip 3, so nothing may start.
        runs [(run-of "x" :other :running) (run-of "y" :other :running)
              (run-of "z" :other :running)]
        d (:decision (loop'/tick {:fleet fleet :roles roles :runs runs :now-ms t0}))]
    (is (= 0 (:free d)))
    (is (empty? (:dispatch d)))
    (is (some #(= :fleet-at-wip (:reason %)) (:withheld d)))))

(deftest dispatch-respects-the-per-tick-allowance
  (testing "global-wip alone would let one tick fill every free slot at once"
    (let [roles [(role-of :a/one :network-isekai) (role-of :a/two :network-isekai)
                 (role-of :a/three :network-isekai)]
          d (:decision (loop'/tick {:fleet fleet :roles roles :runs [] :now-ms t0}))]
      (is (= 3 (:free d)))
      (is (= 1 (:allowance d)))
      (is (= 1 (count (:dispatch d))))
      (is (some #(= :tick-allowance-spent (:reason %)) (:withheld d))))))

(deftest equal-scores-rotate-by-oldest-dispatch
  (testing "an alphabetical prefix must not monopolise capacity"
    (let [roles [(role-of :aaa/one :network-isekai) (role-of :zzz/two :network-isekai)]
          d (:decision (loop'/tick {:fleet fleet :roles roles :runs [] :now-ms t0
                                    :last-dispatch {:aaa/one t0 :zzz/two 0}}))]
      (is (= [:zzz/two] (mapv :role/id (:dispatch d)))))))

(deftest withheld-roles-carry-a-named-reason
  (testing "a loop that reports only what it did is unreadable on quiet ticks"
    (let [roles [(role-of :a/idle :network-isekai :yakuwari/scale {:min 0 :desired 0 :max 1})
                 {:yakuwari/id :a/ghost :yakuwari/business :network-isekai}]
          d (:decision (loop'/tick {:fleet fleet :roles roles :runs [] :now-ms t0}))
          reasons (set (map :reason (:withheld d)))]
      (is (contains? reasons :wants-no-capacity))
      (is (contains? reasons :invalid-spec)))))

(deftest spawn-effects-carry-the-ceilinged-policy-not-the-role-file
  (testing "whoever performs the effect cannot widen a grant by re-reading the role"
    (let [r (role-of :a/marketer :network-isekai
                     :yakuwari/capabilities
                     [{:capability :content.publish :decision :autonomous}])
          e (:effects (loop'/tick {:fleet fleet :roles [r] :runs [] :now-ms t0}))
          spawned (first (:spawn e))]
      (is (= :voice-required (get (:policy spawned) :content.publish))))))

(deftest reaping-precedes-spawning-so-a-freed-slot-can-be-used
  (let [r (role-of :a/one :network-isekai)
        stale (assoc (run-of "s" :a/one :leased t0) :agent.run/lease-grace-ms 1000)
        t (loop'/tick {:fleet fleet :roles [r] :runs [stale] :now-ms (+ t0 5000)})]
    (is (= ["s"] (mapv :run/id (:reap (:effects t)))))
    (is (pos? (count (:spawn (:effects t)))))))

(deftest evidence-is-recorded-even-on-a-tick-that-did-nothing
  (testing "'nothing needed doing' and 'the loop stopped' have opposite fixes"
    (let [roles [(role-of :a/idle :network-isekai :yakuwari/scale {:min 0 :desired 0 :max 1})]
          ev (:evidence (loop'/tick {:fleet fleet :roles roles :runs [] :now-ms t0}))]
      (is (= t0 (:tick/at ev)))
      (is (empty? (:tick/dispatched ev)))
      (is (= {:wants-no-capacity 1} (:tick/withheld-summary ev))))))

(deftest evidence-names-invalid-roles-in-full-rather-than-counting-them
  (testing "a count would not say which role a human must fix"
    (let [roles [{:yakuwari/id :a/ghost :yakuwari/business :network-isekai}]
          ev (:evidence (loop'/tick {:fleet fleet :roles roles :runs [] :now-ms t0}))]
      (is (= [:a/ghost] (:tick/invalid ev))))))

;; ---------------------------------------------------------------------------
;; Shapes the surfaces consume
;; ---------------------------------------------------------------------------

(deftest groups-preserve-declaration-order
  (is (= [:network-isekai] (mapv :group/id (registry/groups businesses)))))

(deftest outward-identities-are-derived-once-not-twice
  (let [roles [(role-of :network-isekai/director :network-isekai
                        :yakuwari/outward? true
                        :yakuwari/identity "network-awai/person-isekai-director"
                        :yakuwari/mailbox "director@isekai.network")
               (role-of :network-isekai/engineer :network-isekai)]
        ids (registry/outward-identities roles)]
    (is (= 1 (count ids)))
    (is (= "network-awai/person-isekai-director" (:identity/repo (first ids))))
    (is (= "director@isekai.network" (:identity/mailbox (first ids))))))

;; ---------------------------------------------------------------------------
;; The acting half: effects become tamaki invocations
;; ---------------------------------------------------------------------------

(def dispatch-cfg
  (dispatch/config fleet {:workspace "/ws"}))

(deftest a-runner-is-picked-by-index-so-a-dispatch-can-be-replayed
  (let [pool [{:runner :claude :weight 2} {:runner :codex :weight 1}]]
    (testing "weights expand to slots rather than probabilities"
      (is (= [:claude :claude :codex] (dispatch/runner-pool pool))))
    (testing "the same n always yields the same runner"
      (is (= :claude (dispatch/runner-for pool 0)))
      (is (= :claude (dispatch/runner-for pool 1)))
      (is (= :codex (dispatch/runner-for pool 2)))
      (is (= :claude (dispatch/runner-for pool 3))))
    (testing "a role nobody can fill yields nil rather than a default runner"
      (is (nil? (dispatch/runner-for [] 0))))))

(deftest fleet-runner-admission-overrides-stale-role-preference
  (let [effect {:role/id :cloud-itonami/supporter
                :role/business :cloud-itonami
                :runners [{:runner :claude :weight 1}]
                :goal "Support one user."
                :policy {}}
        cfg (assoc dispatch-cfg :runners [{:runner :codex :weight 1}])
        submit (dispatch/submit-argv cfg effect "cloud-itonami/app" 0)]
    (is (= :codex (:runner submit)))
    (is (= "codex" (last (:argv submit))))))

(deftest successful-coding-output-is-never-erased
  (is (= :preserved-branch
         (dispatch/proposal-disposition :succeeded false true)))
  (is (= :retained-dirty-worktree
         (dispatch/proposal-disposition :succeeded true true)))
  (is (= :released-no-change
         (dispatch/proposal-disposition :succeeded false false)))
  (is (= :released-failure
         (dispatch/proposal-disposition :failed false true)))
  (is (= :retained-dirty-worktree
         (dispatch/proposal-disposition :failed true true))))

(deftest the-goal-states-the-bound-the-run-executes-under
  (let [goal (dispatch/goal-for
              {:role/id :net-kotobase/sales :role/business :net-kotobase
               :goal "Convert a named\n   engineering organisation."
               :policy {:content.draft :autonomous
                        :mail.send :approval-required
                        :sla.commit :blocked}})]
    (testing "the standing objective survives, without its source indentation"
      (is (str/includes? goal "Convert a named engineering organisation.")))
    (testing "a bound the model is not told about is one it discovers by breaking it"
      (is (str/includes? goal "must not: sla.commit"))
      (is (str/includes? goal "needs human approval before: mail.send")))
    (testing "a grant needs no sentence — the objective already implies it"
      (is (not (str/includes? goal "content.draft"))))))

(deftest a-project-that-is-not-org-slash-repo-is-refused-rather-than-guessed
  (is (= "/ws/orgs/network-awai/net-kotobase"
         (dispatch/project-path dispatch-cfg "network-awai/net-kotobase")))
  (is (nil? (dispatch/project-path dispatch-cfg "net-kotobase")))
  (is (nil? (dispatch/project-path dispatch-cfg "a/b/c")))
  (is (nil? (dispatch/project-path (dissoc dispatch-cfg :workspace)
                                   "network-awai/net-kotobase"))))

(deftest a-submit-carries-the-ceilinged-policy-not-the-role-file
  (let [role (role-of :nexus-x402/marketer :nexus-x402
                      :yakuwari/capabilities
                      [{:capability :content.publish :decision :autonomous}])
        effects (loop'/act fleet [role]
                           {:dispatch [{:role/id :nexus-x402/marketer
                                        :role/business :nexus-x402
                                        :plan {:spawn 1 :reap []}}]})
        s (first (:submits (dispatch/plan dispatch-cfg effects [role] [])))]
    (testing "the fleet ceiling reached the record, so re-reading the role cannot widen it"
      (is (= :voice-required (:content.publish (:policy s)))))
    (testing "and the same narrowed decision reached the prose the runner reads"
      (is (str/includes? (nth (:argv s) 1) "voice before: content.publish")))))

(deftest a-spawn-for-an-unresolvable-project-is-skipped-not-guessed
  (let [role (role-of :nexus-x402/marketer :nexus-x402
                      :yakuwari/project "not-org-shaped")
        effects (loop'/act fleet [role]
                           {:dispatch [{:role/id :nexus-x402/marketer
                                        :role/business :nexus-x402
                                        :plan {:spawn 1 :reap []}}]})
        s (first (:submits (dispatch/plan dispatch-cfg effects [role] [])))]
    (is (= :unresolvable-project (:skip s)))
    (is (nil? (:argv s)))))

(deftest the-runner-rotates-on-what-a-role-already-has
  (let [role (role-of :nexus-x402/marketer :nexus-x402
                      :yakuwari/runners [{:runner :claude :weight 1}
                                         {:runner :codex :weight 1}])
        effects (loop'/act fleet [role]
                           {:dispatch [{:role/id :nexus-x402/marketer
                                        :role/business :nexus-x402
                                        :plan {:spawn 1 :reap []}}]})
        with-none (dispatch/plan dispatch-cfg effects [role] [])
        with-one (dispatch/plan dispatch-cfg effects [role]
                                [(run-of "run-1" :nexus-x402/marketer :running)])]
    (testing "two processes reading the same runs.edn pick the same runner"
      (is (= :claude (:runner (first (:submits with-none)))))
      (is (= :codex (:runner (first (:submits with-one))))))))

(deftest a-reap-cancels-in-tamaki-rather-than-only-forgetting-locally
  (let [effects {:reap [{:effect :reap :role/id :x/y :run/id "run-9"}] :spawn []}
        p (dispatch/plan dispatch-cfg effects [] [])]
    (is (= 1 (count (:cancels p))))
    (is (= ["cancel" "run-9"] (subvec (first (:cancels p)) 0 2)))))

(deftest a-run-tamaki-no-longer-knows-is-reported-not-dropped
  (let [persisted [(run-of "run-1" :a/b :running) (run-of "run-2" :a/b :queued)]
        {:keys [runs orphans]}
        (dispatch/merge-statuses
         persisted
         [{:agent.run/id "run-1" :agent.run/status :succeeded
           :agent.run/updated-at (+ t0 5000)}])]
    (testing "the status tamaki reports wins over the one on record"
      (is (= :succeeded (:agent.run/status (first runs)))))
    (testing "a run that vanished still occupies a slot until someone looks"
      (is (= ["run-2"] (mapv :agent.run/id orphans))))
    (testing "terminal runs stop occupying a slot"
      (is (= ["run-2"] (mapv :agent.run/id (dispatch/prune runs)))))))

(deftest a-queued-run-nobody-leased-is-named-rather-than-reaped
  (let [rs [(run-of "run-1" :a/b :queued t0)
            (run-of "run-2" :a/b :queued (+ t0 900000))
            (run-of "run-3" :a/b :running t0)]
        stuck (dispatch/stuck dispatch-cfg rs (+ t0 900000))]
    (testing "only the one past the grace period, and only because it is queued"
      (is (= ["run-1"] (mapv :agent.run/id stuck)))
      (is (= 900000 (:queued-ms (first stuck)))))))

(deftest the-role-linkage-is-added-because-tamaki-does-not-know-who-asked
  (let [run {:agent.run/id "run-1" :agent.run/status :queued
             :agent.run/goal "g" :agent.run/created-at t0}
        linked (dispatch/link run {:role/id :net-kotobase/sales
                                   :role/business :net-kotobase
                                   :policy {:mail.send :approval-required}})]
    (testing "without this key reconcile/runs-for matches nothing and respawns"
      (is (= :net-kotobase/sales (:agent.run/yakuwari linked))))
    (is (= {:mail.send :approval-required} (:yakuwari/policy linked)))))

(deftest a-submit-is-retargeted-at-an-isolated-tree-not-the-shared-checkout
  (let [role (role-of :net-kotobase/engineer :net-kotobase)
        effects (loop'/act fleet [role]
                           {:dispatch [{:role/id :net-kotobase/engineer
                                        :role/business :net-kotobase
                                        :plan {:spawn 1 :reap []}}]})
        s (first (:submits (dispatch/plan dispatch-cfg effects [role] [])))
        r (dispatch/retarget s "/runs/awai-net-kotobase-engineer-1")]
    (testing "the role resolves the shared checkout; only that is kept as source"
      (is (= "/ws/orgs/network-awai/net-kotobase" (:source-project r))))
    (testing "what the agent may edit is the isolated tree"
      (is (= "/runs/awai-net-kotobase-engineer-1" (:project r)))
      (is (= "/runs/awai-net-kotobase-engineer-1"
             (nth (:argv r) (inc (.indexOf (to-array (:argv r)) "--project"))))))
    (testing "the shared checkout appears nowhere in what tamaki is told"
      (is (not (some #{"/ws/orgs/network-awai/net-kotobase"} (:argv r)))))
    (testing "the goal is not rebuilt, only the project value is replaced"
      (is (= (nth (:argv s) 1) (nth (:argv r) 1))))))

(deftest a-worktree-branch-names-the-role-and-cannot-collide-with-a-repo-branch
  (is (= "awai/net-kotobase-engineer-1785"
         (dispatch/worktree-branch :net-kotobase/engineer 1785)))
  (is (str/starts-with? (dispatch/worktree-branch :a/b 1) "awai/")))

(deftest dispatch-refuses-to-run-in-a-shared-tree-when-no-worktree-root-is-set
  (testing "absent is the default, and absent means refuse rather than fall back"
    (is (nil? (:worktree-root (dispatch/config {}))))
    (is (= "/runs" (:worktree-root (dispatch/config
                                    {:awai.fleet/dispatch {:worktree-root "/runs"}}))))))
