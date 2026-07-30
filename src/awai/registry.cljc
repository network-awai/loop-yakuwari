(ns awai.registry
  "The awai role registry: businesses, their roles, and the fleet bound above
  both.

  Pure and portable. Reading files is the caller's job (`bin/awai.cljs`), so
  this namespace can run in a Worker, a JVM job and an nbb script without
  three copies drifting apart.

  It owns no domain truth: `yakuwari.spec` decides whether a role is
  well-formed and `yakuwari.policy` owns the HIL vocabulary. What this adds is
  the part neither can see — whether `businesses.edn` and the files under
  `yakuwari/` still agree with each other, and whether the fleet ceiling in
  `fleet.edn` has actually been applied.

  ## Why the cross-checks are the point

  Two files describing one org chart drift. A business can list a `sales` role
  it never got, a role file can name a business nobody declared, and an
  outward role can lose the mailbox that made it outward — each of which is
  invisible from inside either file alone. `validate-fleet` reports all of
  them at once, following `yakuwari.spec`'s own rule that one error per round
  trip turns an N-field form into an N-step one."
  (:require [clojure.string :as str]
            [yakuwari.spec :as spec]
            [yakuwari.policy :as policy]))

;; ---------------------------------------------------------------------------
;; The fleet ceiling
;; ---------------------------------------------------------------------------

(defn ceilinged-policy
  "A role's effective policy, narrowed by the fleet ceiling.

  `policy/strictest-of` resolves each capability, so listing one in
  `:awai.fleet/capability-ceiling` can only ever REDUCE a role's autonomy —
  never grant it. That direction is what makes the ceiling safe to widen a
  role file against: a business owner reviewing their own roles cannot
  accidentally escape a fleet-level bound, and the fleet cannot accidentally
  hand out a permission a business never asked for.

  A capability the ceiling does not mention is left exactly as the role wrote
  it. The ceiling is a bound, not a whitelist — making it a whitelist would
  mean every new capability needed a fleet-level edit before any business
  could use it."
  [fleet role]
  (let [ceiling (or (:awai.fleet/capability-ceiling fleet) {})
        own (spec/effective-policy role)]
    (reduce (fn [acc [cap d]]
              (if (contains? acc cap)
                (assoc acc cap (policy/strictest-of [(get acc cap) d]))
                acc))
            own
            ceiling)))

(defn decide
  "What may this role actually do, fleet ceiling included? The only decision
  function callers should use — `spec/decide` answers what the role file
  claims, which is not the same thing."
  [fleet role capability]
  (policy/decide (ceilinged-policy fleet role) capability))

(defn ceiling-narrowings
  "Where the fleet ceiling actually changed a role's answer. Reported rather
  than applied silently: a role file that reads `:autonomous` while the fleet
  answers `:approval-required` is confusing to whoever reviews the role, and
  the honest fix is to show them the difference."
  [fleet role]
  (let [own (spec/effective-policy role)
        final (ceilinged-policy fleet role)]
    (vec (for [[cap d] final
               :let [before (get own cap)]
               :when (not= before d)]
           {:capability cap :role-says before :fleet-says d}))))

;; ---------------------------------------------------------------------------
;; Cross-file agreement
;; ---------------------------------------------------------------------------

(defn- business-index [businesses]
  (into {} (map (juxt :business/id identity)
                (:awai.businesses/businesses businesses))))

(defn validate-fleet
  "Every disagreement between `businesses.edn`, the role files and
  `fleet.edn`, reported at once.

  Returns `{:ok? bool :problems [...] :counts {...}}`. Problems carry enough
  to fix them without re-deriving anything:

    :role-unknown-business    a role names a business nobody declared
    :role-not-in-business     the business exists but does not list this role
    :business-role-missing    the business lists a role that has no file
    :duplicate-role-id        two files claim one id, so runs cannot be attributed
    :outward-without-identity a role corresponds outward with no person-* repo
    :outward-without-mailbox  ... or no address for mail to arrive at
    :identity-without-outward an identity on a role not declared outward
    :non-outward-role-outward a role outside the fleet's outward set claims to be
    :absent-role-present      :roles-absent names a role that actually exists
    :invalid-spec             yakuwari.spec refused it"
  [{:keys [fleet businesses roles]}]
  (let [bx (business-index businesses)
        outward-kinds (or (:awai.businesses/outward-roles businesses) #{})
        by-id (group-by :yakuwari/id roles)
        problems
        (vec
         (concat
          ;; A role id must be unique fleet-wide: reconcile/plan attributes a
          ;; run by matching :agent.run/yakuwari to :yakuwari/id, so two roles
          ;; sharing one id would silently pool each other's executions.
          (for [[id rs] by-id :when (> (count rs) 1)]
            {:problem :duplicate-role-id :role/id id :count (count rs)})

          (mapcat (fn [r]
                    (let [id (:yakuwari/id r)
                          biz (:yakuwari/business r)
                          b (get bx biz)
                          ;; The role's own kind, from the id's name:
                          ;; :net-kotobase/sales -> :sales.
                          kind (when id (keyword (name id)))
                          v (spec/validate r)]
                      (concat
                       (when-not (:ok? v)
                         [{:problem :invalid-spec :role/id id
                           :problems (:problems v)}])
                       (if (nil? b)
                         [{:problem :role-unknown-business :role/id id
                           :business biz}]
                         (when-not (contains? (set (:business/roles b)) kind)
                           [{:problem :role-not-in-business :role/id id
                             :business biz :kind kind
                             :declared (vec (:business/roles b))}]))
                       ;; Outward is a claim with a mailbox and a did:web
                       ;; behind it. A role claiming it without either is a
                       ;; role nothing can actually reach.
                       (when (:yakuwari/outward? r)
                         (concat
                          (when (str/blank? (str (:yakuwari/identity r)))
                            [{:problem :outward-without-identity :role/id id}])
                          (when (str/blank? (str (:yakuwari/mailbox r)))
                            [{:problem :outward-without-mailbox :role/id id}])
                          (when (and (seq outward-kinds)
                                     (not (contains? outward-kinds kind)))
                            [{:problem :non-outward-role-outward :role/id id
                              :kind kind :fleet-outward outward-kinds}])))
                       ;; The reverse: an identity on a role nobody declared
                       ;; outward means a person-* repo exists that no surface
                       ;; will ever mark as reachable.
                       (when (and (not (:yakuwari/outward? r))
                                  (not (str/blank? (str (:yakuwari/identity r)))))
                         [{:problem :identity-without-outward :role/id id
                           :identity (:yakuwari/identity r)}]))))
                  roles)

          ;; The other direction: a business promising a role that has no file.
          (mapcat (fn [b]
                    (let [present (set (for [r roles
                                             :when (= (:business/id b)
                                                      (:yakuwari/business r))]
                                         (keyword (name (:yakuwari/id r)))))]
                      (concat
                       (for [kind (:business/roles b)
                             :when (not (contains? present kind))]
                         {:problem :business-role-missing
                          :business (:business/id b) :kind kind})
                       ;; And a documented absence that is not absent. An
                       ;; explanation for why a role does not exist, sitting
                       ;; next to the role, is worse than no explanation.
                       (for [{:keys [role]} (:business/roles-absent b)
                             :when (contains? present role)]
                         {:problem :absent-role-present
                          :business (:business/id b) :kind role}))))
                  (:awai.businesses/businesses businesses))))]
    {:ok? (empty? problems)
     :problems problems
     :counts {:businesses (count (:awai.businesses/businesses businesses))
              :roles (count roles)
              :outward (count (filter :yakuwari/outward? roles))
              :narrowed-by-ceiling
              (count (filter #(seq (ceiling-narrowings fleet %)) roles))}}))

;; ---------------------------------------------------------------------------
;; Shapes the surfaces want
;; ---------------------------------------------------------------------------

(defn groups
  "`businesses.edn` -> the `:groups` argument `yakuwari-view.model/project`
  takes, in declaration order."
  [businesses]
  (mapv (fn [b]
          {:group/id (:business/id b)
           :group/name (:business/name b)
           :group/domain (:business/domain b)
           :group/repo (:business/repo b)
           :group/what (:business/what b)
           :group/roles-absent (vec (:business/roles-absent b))})
        (:awai.businesses/businesses businesses)))

(defn outward-identities
  "Every person-* repo the registry expects to exist, with the mailbox it
  answers on. The scaffolder reads this rather than re-deriving the list from
  role names — deriving it twice is how the two drift."
  [roles]
  (vec (for [r roles :when (:yakuwari/outward? r)]
         {:identity/repo (:yakuwari/identity r)
          :identity/mailbox (:yakuwari/mailbox r)
          :identity/role (:yakuwari/id r)
          :identity/business (:yakuwari/business r)})))
