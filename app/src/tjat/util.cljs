(ns tjat.util
  (:require [clojure.pprint]))

(defn single [[e & es]]
  (assert (nil? es) (str "Expected length 0 or 1, got " (count es)))
  e)

(defn spprint [d]
  (with-out-str
    (clojure.pprint/pprint d)))

(defn swap-elements [v i1 i2]
  {:pre [(vector? v)]}
  (assoc v i2 (v i1) i1 (v i2)))