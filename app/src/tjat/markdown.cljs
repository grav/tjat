(ns tjat.markdown
  (:require ["showdown" :as showdown]))

(def think-start
  (clj->js
    {:type "lang"
     :regex #"<think>"
     :replace "<small><details><summary><i>Think</i> \uD83D\uDCAD</summary><i>"}))

(def think-end
  (clj->js
    {:type "lang"
     :regex #"</think>"
     :replace "</i><hr></details></small>"}))

(defn markdown->html [text]
  (.makeHtml
    ;; https://github.com/showdownjs/showdown?tab=readme-ov-file#valid-options
    (doto (showdown/Converter.
            (clj->js {:extensions [think-start
                                   think-end]}))
      (.setFlavor "github")) text))

(defn markdown-view [text]
  [:p
   {:dangerouslySetInnerHTML
    {:__html (markdown->html text)}}])