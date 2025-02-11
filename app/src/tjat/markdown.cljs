(ns tjat.markdown
  (:require [hickory.core :as h]
            [hickory.utils :as hu]
            [markdown.core :as md]
            [goog.dom :as dom]))

(defn- as-seq [nodelist]
  (if (seq? nodelist) nodelist (array-seq nodelist)))

(defn node-type
  [type]
  "Works for both Node and Browser"
  (or (aget js/Node (str (clojure.string/upper-case (name type)) "_NODE"))
      (aget js/Node (clojure.string/upper-case (name type)))))

(defn as-hiccup' [this]
  ;; weird - all but last expr is nil in shadow-cljs release-mode?
  #_(println 'element h/Element (h/node-type "ELEMENT") (aget js/Node (str "ELEMENT" "_NODE")))
  (condp = (aget this "nodeType")
    (node-type :attribute) [(hu/lower-case-keyword (aget this "name"))
                            (aget this "value")]
    (node-type :comment) (str "<!--" (aget this "data") "-->")
    (node-type :document) (map as-hiccup' (as-seq (aget this "childNodes")))
    (node-type :document_type) (h/format-doctype this)
    ;; There is an issue with the hiccup format, which is that it
    ;; can't quite cover all the pieces of HTML, so anything it
    ;; doesn't cover is thrown into a string containing the raw
    ;; HTML. This presents a problem because it is then never the case
    ;; that a string in a hiccup form should be html-escaped (except
    ;; in an attribute value) when rendering; it should already have
    ;; any escaping. Since the HTML parser quite properly un-escapes
    ;; HTML where it should, we have to go back and un-un-escape it
    ;; wherever text would have been un-escaped. We do this by
    ;; html-escaping the parsed contents of text nodes, and not
    ;; html-escaping comments, data-nodes, and the contents of
    ;; unescapable nodes.
    (node-type :element) (let [tag (hu/lower-case-keyword (aget this "tagName"))]
                           (into [] (concat [tag
                                             (into {} (map as-hiccup' (as-seq (aget this "attributes"))))]
                                            (if (hu/unescapable-content tag)
                                              (map dom/getRawTextContent (as-seq (aget this "childNodes")))
                                              (map as-hiccup' (as-seq (aget this "childNodes")))))))
    (node-type :text) (dom/getRawTextContent this)))


(def concatv
  (comp vec concat))

(defn md'->hiccup [s & [{:keys [keep-top-margin]}]]
  (when s
    (cond-> (concatv [:div {}]
                     (->> (clojure.string/replace-all (or s "") #"([^\n])\n([^\n])" "$1  \n$2")
                          md/md->html
                          hickory.core/parse-fragment
                          (map as-hiccup')))
            (nil? keep-top-margin) (update-in [2 1 :style] merge {:margin 0}))))

(comment
  (-> (str "*TODO*")
      md'->hiccup))