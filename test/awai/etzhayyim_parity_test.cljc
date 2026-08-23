(ns awai.etzhayyim-parity-test
  "The etzhayyim roles mirror Tamaki ActorSpecs. This holds the two sides in
  agreement, in both directions:

    - every role carrying `:tamaki/actor` points at an ActorSpec file that
      exists, whose `:actor/id` is the one named, and whose objective the
      role repeats VERBATIM -- or the role says, in `:tamaki/objective-differs`,
      why it does not. A silent restatement is the failure this test exists
      for: two copies of one objective that drift apart without anyone
      deciding to.
    - every ActorSpec under `actors/` is either projected or named in
      `:business/roles-absent` with a reason. An actor nobody projected and
      nobody declined is an actor that fell out.

  The Tamaki checkout is found the way `bin/awai.cljs` finds the workspace:
  `AWAI_WORKSPACE`, else the nearest ancestor holding `manifest/west.yml`.
  Not finding it is a FAILURE, not a skip -- a parity check that cannot see
  one side and reports green is the silence CLAUDE.md's six questions are
  about."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            #?(:cljs ["fs" :as fs])
            #?(:cljs ["path" :as path])))

#?(:cljs
   (do
     (defn- cwd [] (.cwd js/process))

     (defn- workspace []
       (or (.. js/process -env -AWAI_WORKSPACE)
           (loop [d (cwd) depth 0]
             (cond
               (fs/existsSync (path/join d "manifest" "west.yml")) d
               (or (> depth 8) (= d (path/dirname d))) nil
               :else (recur (path/dirname d) (inc depth))))))

     (defn- read-edn [p] (edn/read-string (fs/readFileSync p "utf8")))

     (defn- tamaki-root []
       (some-> (workspace) (path/join "orgs" "etzhayyim" "tamaki")))

     (defn- roles []
       (read-edn (path/join (cwd) "yakuwari" "etzhayyim.edn")))

     (defn- business []
       (->> (read-edn (path/join (cwd) "businesses.edn"))
            :awai.businesses/businesses
            (some #(when (= :etzhayyim (:business/id %)) %))))

     (defn- actor-files []
       (let [dir (path/join (tamaki-root) "actors")]
         (->> (fs/readdirSync dir)
              (filter #(str/ends-with? % ".edn"))
              (map #(path/join dir %))
              sort)))

     (deftest the-tamaki-checkout-is-where-the-workspace-says
       (let [root (tamaki-root)]
         (is (some? root)
             "no workspace: set AWAI_WORKSPACE or run from under the superproject")
         (is (and root (fs/existsSync (path/join root "actors")))
             (str "tamaki actors/ not found at " root
                  " -- the parity check cannot run, and refuses to pass"))))

     (deftest every-mirrored-role-repeats-its-actor-or-says-why
       (let [root (tamaki-root)]
         (doseq [r (roles)]
           (testing (str (:yakuwari/id r))
             (is (keyword? (:tamaki/actor r)) ":tamaki/actor names the ActorSpec id")
             (is (string? (:tamaki/spec r)) ":tamaki/spec names the ActorSpec file")
             (let [f (path/join root (:tamaki/spec r))]
               (is (fs/existsSync f) (str "ActorSpec file exists: " f))
               (when (fs/existsSync f)
                 (let [a (read-edn f)]
                   (is (= (:tamaki/actor r) (:actor/id a))
                       "the role names the id the file declares")
                   (if-let [why (:tamaki/objective-differs r)]
                     (is (and (string? why) (not (str/blank? why))
                              (not= (:yakuwari/objective r) (:actor/objective a)))
                         "a stated difference is a real difference with a reason")
                     (is (= (:yakuwari/objective r) (:actor/objective a))
                         "objective is verbatim from the ActorSpec, or the role says why not")))))))))

     (deftest every-actor-is-projected-or-declined
       (let [projected (set (map :tamaki/actor (roles)))
             declined (set (map :role (:business/roles-absent (business))))
             actors (for [f (actor-files)
                          :let [a (read-edn f)]
                          ;; actors/revenue-targets.edn is a KPI threshold
                          ;; file, not an actor: no :actor/id.
                          :when (:actor/id a)]
                      [(path/basename f) (:actor/id a)])]
         (is (seq actors) "at least one ActorSpec was read")
         (doseq [[file id] actors]
           (testing file
             (is (or (contains? projected id)
                     (contains? declined (keyword (name id))))
                 (str id " is neither projected in yakuwari/etzhayyim.edn nor"
                      " declined in businesses.edn :roles-absent"))))))

     (deftest the-six-run-under-the-named-profile
       (let [profiles (read-edn (path/join (cwd) "profiles.edn"))
             by-key (:awai.profiles/by-key profiles)]
         (doseq [r (roles)
                 :let [k (str "etzhayyim/" (name (:yakuwari/id r)))]]
           (testing k
             (is (= :tamaki-resident (get by-key k))
                 "assigned explicitly, not inherited from :default")))
         (is (contains? (:awai.profiles/profiles profiles) :tamaki-resident))))))
