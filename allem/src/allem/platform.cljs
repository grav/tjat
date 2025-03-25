(ns allem.platform
  (:require [goog.string :as gstring] goog.string.format))  ;; https://clojurescript.org/reference/google-closure-library#requiring-a-function))

(defn format' [s & args]
      (apply gstring/format s args))