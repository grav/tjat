(ns tjat.components.response
  (:require ["showdown" :as showdown]))

(defn response-view 
  "Renders a markdown response with timestamp"
  [{:keys [request-time id text] :as response}]
  (when id
    [:div
     [:div [:i (some-> request-time
                      js/Date.parse
                      (js/Date.)
                      str)]]
     [:div [:p
            {:dangerouslySetInnerHTML
             {:__html (.makeHtml
                        ;; https://github.com/showdownjs/showdown?tab=readme-ov-file#valid-options
                        (doto (showdown/Converter.)
                          (.setFlavor "github")) text)}}]]]))

(defn response-tabs 
  "Tabs for selecting between different model responses"
  [{:keys     [selected-response-id]
    chat-id   :id
    responses :responses}
   {:keys [on-response-select]}]
  [:div {:style {:display :flex}}
   (for [[i {:keys [model id] :as v}] (map vector (range) (sort-by :time responses))]
     ^{:key id} [:div [:div
                       {:style    {:padding 10}
                        :on-click #(on-response-select [chat-id id])}
                       [:div {:style {:background-color (when (or (= selected-response-id id)
                                                                (and (nil? selected-response-id)
                                                                     (zero? i))) :lightgray)}}
                        (name model)]]])])