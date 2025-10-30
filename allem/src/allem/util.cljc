(ns allem.util
  (:require [clojure.pprint]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [shadow.resource :as rc]))


(defn single [[e & es]]
  (assert (nil? es) (str "Expected length 0 or 1, got " (count es)))
  e)

(defn spprint [d]
  (with-out-str
    (clojure.pprint/pprint d)))

(defn tap-> [v]
  (doto v tap>))



(def provider->api-key-env-var
  {:together-ai "TOGETHERAI_API_KEY"})

(defn key->env-var [v]
  (or (get provider->api-key-env-var v)
    (-> v
        name
        str/upper-case
        (str/replace "-" "_"))))

(defn date->str [date]
  (some-> date
          js/Date.parse
          (js/Date.)
          str))

#_(defn get-api-key [provider]
    (or (get api-keys provider)
        (System/getenv (key->env-var provider))
        (throw (ex-info "Couldn't find key" {:key provider}))))

(defn is-text-mimetype? [s]
  (str/starts-with? s "text/"))
      