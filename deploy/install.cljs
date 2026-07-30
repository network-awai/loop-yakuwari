#!/usr/bin/env nbb
;; Install the residency LaunchAgents.
;;
;; nbb, not bash — CLAUDE.md prohibits new .sh files (owner directive
;; 2026-07-14) and ADR-2607173000 retires `bb` as a script host.
;;
;;   nbb deploy/install.cljs            # what it would do
;;   nbb deploy/install.cljs --apply    # write plists and bootstrap them
;;
;; Idempotent: re-running replaces the plists and kickstarts the agents.
;;
;; WHY A LAUNCHAGENT AND NOT A WORKER. Residency for this loop means the tick
;; runs on murakumo's own node, the same shape cloud-murakumo's organism uses:
;; a Cloudflare Worker cannot run a JVM/Chicory host, and the only thing that
;; should ever reach the edge is the resulting static artifact. Being resident
;; changes latency and cost, never authority (kotoba-lang/yakuwari) — nothing
;; installed here grants a role more than fleet.edn allows.

(ns awai.install
  (:require [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["child_process" :as cp]))

(def apply? (boolean (some #{"--apply"} *command-line-args*)))

(def repo (path/resolve (path/join (path/dirname (or (first *command-line-args*)
                                                     "deploy/install.cljs"))
                                   "..")))

(def repo-root
  "Resolved by finding fleet.edn rather than trusting cwd, for the same reason
  bin/awai.cljs does: a wrong root silently yields an empty registry, which
  looks like success."
  (loop [d (path/resolve (.cwd js/process)) n 0]
    (cond
      (fs/existsSync (path/join d "fleet.edn")) d
      (or (> n 8) (= d (path/dirname d)))
      (throw (ex-info "no fleet.edn found — run from the repo" {}))
      :else (recur (path/dirname d) (inc n)))))

(def home (os/homedir))
(def agents-dir (path/join home "Library" "LaunchAgents"))

(defn- which [bin]
  (try (str/trim (str (cp/execSync (str "command -v " bin) #js {:encoding "utf8"})))
       (catch :default _ nil)))

(def nbb-path
  (or (which "nbb")
      (throw (ex-info "nbb not on PATH — install it before installing agents" {}))))

(defn- sibling-check
  "The classpath reaches ../../kotoba-lang/{yakuwari,yakuwari-view}. If those
  are not checked out the agent will fail every 300 seconds and only the log
  will say why, so refuse now instead."
  []
  (let [missing (remove #(fs/existsSync (path/join repo-root ".." ".."
                                                   "kotoba-lang" % "src"))
                        ["yakuwari" "yakuwari-view"])]
    (when (seq missing)
      (println (str "REFUSING: missing sibling checkout(s): "
                    (str/join ", " (map #(str "kotoba-lang/" %) missing))))
      (println "  west update --fetch smart yakuwari yakuwari-view")
      (.exit js/process 1))))

(defn- render [tpl]
  (-> tpl
      (str/replace "@REPO@" repo-root)
      (str/replace "@NBB@" nbb-path)
      (str/replace "@HOME@" home)))

(def labels ["network.awai.yakuwari.tick" "network.awai.yakuwari.publish"])

(defn -main []
  (sibling-check)
  (fs/mkdirSync (path/join home ".gftd") #js {:recursive true})
  (doseq [label labels]
    (let [tpl-path (path/join repo-root "deploy" (str label ".plist.template"))
          out (path/join agents-dir (str label ".plist"))
          body (render (str (fs/readFileSync tpl-path "utf8")))]
      (if-not apply?
        (println (str "would write " out))
        (do
          (fs/mkdirSync agents-dir #js {:recursive true})
          (fs/writeFileSync out body)
          (println (str "wrote " out))
          ;; bootout before bootstrap so a changed plist actually takes
          ;; effect; ignore the failure when it was not loaded.
          (try (cp/execSync (str "launchctl bootout gui/" (.getuid js/process)
                                 "/" label " 2>/dev/null"))
               (catch :default _ nil))
          (cp/execSync (str "launchctl bootstrap gui/" (.getuid js/process) " " out))
          (cp/execSync (str "launchctl kickstart -p gui/" (.getuid js/process)
                            "/" label))
          (println (str "bootstrapped " label))))))
  (when-not apply?
    (println "dry-run: nothing written (--apply to install)"))
  (println (str "repo: " repo-root))
  (println (str "nbb:  " nbb-path)))

(-main)
