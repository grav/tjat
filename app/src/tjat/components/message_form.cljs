(ns tjat.components.message-form
  (:require [tjat.ui :as ui]))

(defn message-form 
  "Component for sending messages to the AI"
  [{:keys [text loading]}
   {:keys [on-text-change on-submit]}]
  [:div
   [:p
    [:textarea
     {:style {:width "100%"}
      :rows  10
      :value text
      :on-change
      (fn [e]
        (on-text-change (.-value (.-target e))))}]]
   [:p
    {:style {:height 50}}
    [:button {:disabled (empty? text)
              :on-click on-submit}
     "submit"]
    (when loading
      [ui/spinner])]])