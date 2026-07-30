(ns awai.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [yakuwari.spec :as spec]
            [awai.registry :as registry]
            [awai.loop :as loop']))

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
