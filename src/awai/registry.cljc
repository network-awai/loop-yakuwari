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

(defn complete-roles
  "Fill declared startup jobs that do not have a richer authored role map.

  Authored roles always win. Templates create membership, objectives and
  policy; they never create a role the business did not declare. Outward
  identity remains explicit business data rather than being guessed from a
  domain or role name."
  [businesses workforce authored]
  (let [templates (:awai.workforce/templates workforce)
        authored-by-id (into {} (map (juxt :yakuwari/id identity)) authored)
        generated
        (for [business (:awai.businesses/businesses businesses)
              kind (:business/roles business)
              :let [id (keyword (name (:business/id business)) (name kind))
                    template (get templates kind)]
              :when (and template (not (contains? authored-by-id id)))
              :let [outward (get-in business [:business/outward kind])
                    focus (get-in business [:business/job-focus kind])]]
          (cond->
           {:yakuwari/id id
            :yakuwari/business (:business/id business)
            :yakuwari/project (:business/repo business)
            :yakuwari/objective
            (str (:yakuwari/objective template)
                 " Business context: " (:business/what business)
                 (when focus (str " Product-specific focus: " focus)))
            :yakuwari/scale (:yakuwari/scale template)
            :yakuwari/runners (:yakuwari/runners template)
            :yakuwari/capabilities (:yakuwari/capabilities template)
            :bot/role (:bot/role template)
            :bot/name (:bot/name template)
            :bot/cadence-minutes (:bot/cadence-minutes template)
            :yakuwari/generated-from :awai.workforce/template}
            outward
            (assoc :yakuwari/outward? true
                   :yakuwari/identity (:identity outward)
                   :yakuwari/mailbox (:mailbox outward))))]
    (vec (concat authored generated))))

(def max-objective-chars
  "Cloud Itonami's `bot/max-responsibility` (cloud-itonami-app bot.cljc). The
  projection puts the objective verbatim into :responsibilities, so an
  objective over this is refused at provisioning — for every role at once."
  1000)

(def authority-shaped-profile-keys
  "Keys a profile may never carry.

  A profile says how a role RUNS. Authority has one path -- `:yakuwari/
  capabilities`, carried as a biscuit, decided by `kotoba-lang/authority` --
  and a second surface able to widen a Bot's reach is what ADR-2608180200
  refuses. These are REJECTED rather than ignored: ignoring lets someone write
  `:profile/tools` and believe it did something."
  #{:profile/capabilities :profile/tools :profile/scopes :profile/writes?
    :profile/accounts :profile/approvals :profile/omakase? :profile/browser?
    :profile/peers?})

(defn role-key
  "The stable name of a role, `\"business/kind\"` -- the same string the
  projection emits as `:key` and the same one Cloud Itonami stores as
  `:bot/workforce-key`. `profiles.edn` addresses roles by THIS, because it is
  the name that already appears everywhere else."
  [businesses role]
  ;; A role may name a business that does not exist -- `validate-fleet` reports
  ;; that as :role-unknown-business. Falling back to the declared name keeps
  ;; this total, so the profile checks do not throw before that is reported.
  (let [b (get (business-index businesses) (:yakuwari/business role))
        biz (or (:business/id b) (:yakuwari/business role))]
    (str (when biz (name biz)) "/" (name (:yakuwari/id role)))))

(defn profile-for
  "The profile a role runs under: its own key, else its kind, else `:default`.

  Returns nil when `profiles` is absent, so a deployment without the file
  behaves exactly as it did before one existed.

  `by-key` is addressed by `role-key` -- \"animeka/work-hagane\", the string
  the projection already emits. An earlier version looked up `:yakuwari/id`
  instead, so every `by-key` entry matched nothing and the table sat there
  doing NOTHING while looking like it worked. `validate-fleet` now refuses an
  entry that resolves no role, because silence is what made that survive."
  [businesses profiles role]
  (when-let [table (:awai.profiles/profiles profiles)]
    (let [kind (keyword (name (:yakuwari/id role)))
          chosen (or (get (:awai.profiles/by-key profiles) (role-key businesses role))
                     (get (:awai.profiles/by-role profiles) kind)
                     :default)]
      (get table chosen))))

(defn workforce-bots
  "Project effective roles into Cloud Itonami Bot jobs.

  The projection carries semantic capability decisions for explanation. It
  is not an execution grant: Cloud Itonami still intersects each entry with
  the Bot's concrete tool/workspace grant."
  [{:keys [fleet businesses workforce roles profiles]}]
  (let [bx (business-index businesses)
        templates (:awai.workforce/templates workforce)]
    {:schema "network.awai.workforce-bots.v1"
     :businesses (count (:awai.businesses/businesses businesses))
     :roles
     (mapv
      (fn [role]
        (let [business (get bx (:yakuwari/business role))
              kind (keyword (name (:yakuwari/id role)))
              template (get templates kind)
              effective (ceilinged-policy fleet role)
              caps (mapv (fn [entry]
                           {:capability (:capability entry)
                            :decision (get effective (:capability entry) :blocked)
                            :note (:note entry)})
                         (:yakuwari/capabilities role))]
          {:key (str (name (:business/id business)) "/" (name kind))
           :business {:id (:business/id business)
                      :name (:business/name business)
                      :domain (:business/domain business)
                      :repo (:business/repo business)
                      :what (:business/what business)
                      ;; The tenant this business belongs to, by slug. nil for
                      ;; the thirteen that predate the key: Cloud Itonami
                      ;; provisions those into whichever personal tenant asks,
                      ;; and a business that names an organization ONLY into
                      ;; that organization's tenant. See businesses.edn
                      ;; :etzhayyim for why the key exists.
                      :organization (:business/organization business)}
           :role {:id kind
                  :job (or (:bot/role role) (:bot/role template) kind)
                  :name (or (:bot/name role) (:bot/name template)
                            (str/capitalize (str/replace (name kind) #"-" " ")))}
           :objective (:yakuwari/objective role)
           :responsibilities
           [(:yakuwari/objective role)
            "Act only inside this business and leave cross-business work blocked."
            "Record observed evidence separately from forecasts and proposals."]
           :capabilities caps
           :project (:yakuwari/project role)
           :workspace (str "orgs/" (:yakuwari/project role))
           :cadence-minutes (or (:bot/cadence-minutes role)
                                (:bot/cadence-minutes template)
                                1440)
           ;; How this role runs. Never what it may do -- see
           ;; `authority-shaped-profile-keys`.
           :profile (when-let [p (profile-for businesses profiles role)]
                      (select-keys p [:profile/id :profile/provider :profile/model
                                      :profile/cadence-minutes]))
           :outward? (boolean (:yakuwari/outward? role))
           :identity (:yakuwari/identity role)
           :mailbox (:yakuwari/mailbox role)}))
      (sort-by (juxt (comp str :yakuwari/business)
                     (comp str :yakuwari/id)) roles))}))

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
    :invalid-spec             yakuwari.spec refused it
    :objective-over-consumer-limit  an objective longer than the Bot
                              projection's consumer accepts (Cloud Itonami
                              bounds each responsibility at 1000 chars and
                              refuses the WHOLE provision, so one long
                              objective stalls every Bot's registry update)
    :profile-carries-authority a profile named something only the capability
                              path may name
    :profile-key-matches-no-role   an assignment that reaches no role at all
    :profile-role-matches-no-role  ... by kind
    :profile-assignment-names-unknown-profile  assigned to a profile that is
                              not in the table
    :profiles-without-default a profile table with no :default to fall back to"
  [{:keys [fleet businesses roles profiles]}]
  (let [bx (business-index businesses)
        outward-kinds (or (:awai.businesses/outward-roles businesses) #{})
        by-id (group-by :yakuwari/id roles)
        problems
        (vec
         (concat
          ;; A profile says how a role runs. It may not say what it may do:
          ;; rejected rather than ignored, so a `:profile/tools` that would
          ;; have done nothing is a failure instead of a false belief.
          (for [[id p] (:awai.profiles/profiles profiles)
                k (keys p)
                :when (contains? authority-shaped-profile-keys k)]
            {:problem :profile-carries-authority :profile id :key k})

          ;; A profile assignment that resolves nothing is the failure this
          ;; file already had once: `by-key` was looked up with the wrong
          ;; value, so every entry matched no role and the table sat there
          ;; doing nothing while looking like it worked. Found only by
          ;; assigning one live and watching NOTHING change. An assignment
          ;; that reaches no role, or names a profile that does not exist, is
          ;; now a failure rather than a silence.
          (let [known (set (map (partial role-key businesses) roles))
                table (:awai.profiles/profiles profiles)
                kinds (set (map (comp keyword name :yakuwari/id) roles))]
            (concat
             (for [[k id] (:awai.profiles/by-key profiles)
                   :when (not (contains? known k))]
               {:problem :profile-key-matches-no-role :key k :profile id
                :hint "addressed as \"business/kind\" -- the same string the projection emits as :key"})
             (for [[k id] (:awai.profiles/by-role profiles)
                   :when (not (contains? kinds k))]
               {:problem :profile-role-matches-no-role :role k :profile id})
             (for [[k id] (merge (:awai.profiles/by-key profiles)
                                 (:awai.profiles/by-role profiles))
                   :when (not (contains? table id))]
               {:problem :profile-assignment-names-unknown-profile :assignment k :profile id})
             (when (and table (not (contains? table :default)))
               [{:problem :profiles-without-default
                 :hint "every role falls back to :default; without it a role runs on whatever the app hardcodes"}])))

          ;; A role id must be unique fleet-wide: reconcile/plan attributes a
          ;; run by matching :agent.run/yakuwari to :yakuwari/id, so two roles
          ;; sharing one id would silently pool each other's executions.
          (for [[id rs] by-id :when (> (count rs) 1)]
            {:problem :duplicate-role-id :role/id id :count (count rs)})

          ;; Measured 2026-08-22: a 1,154-character objective passed `check`
          ;; and then `itonami bots provision` refused the entire 92-role
          ;; catalog with ":bot/responsibility is longer than 1000
          ;; characters". The limit is the consumer's, but the registry is
          ;; where the author is, so the registry says it first.
          (for [r roles
                :let [n (count (str (:yakuwari/objective r)))]
                :when (> n max-objective-chars)]
            {:problem :objective-over-consumer-limit :role/id (:yakuwari/id r)
             :chars n :limit max-objective-chars
             :hint "point at a file in the role's project for the long version"})

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
