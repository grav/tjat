(ns tjat.core
  (:require ["react-dom/client" :refer [createRoot]]
            [allem.util :as util]
            [reagent.core :as r]
            [goog.dom :as gdom]
            [shadow.resource :as rc]
            [shadow.resource :as rc]
            [allem.core]
            [allem.util :as utils]
            [httpurr.client :as http]
            [httpurr.client.xhr-alt :refer [client]]
            [tjat.markdown :as markdown]))

(defonce root (createRoot (gdom/getElement "app")))

(defonce !state (r/atom nil))
(def foo
  (rc/inline "keys.edn"))

(defn do-request! [{:keys [message model]}]
  (let [{:keys [reply-fn headers url body]} (allem.core/apply-config
                                              {:model   model
                                               :message message})]
    (-> (http/send! client {:method :post
                            :url url
                            :headers headers
                            :body (js/JSON.stringify (clj->js body))})
        (.then #(get % :body))
        (.then js/JSON.parse)
        (.then #(js->clj % :keywordize-keys true))
        (.then reply-fn))))

(comment
  (-> (do-request! {:message "yolo"
                    :model :gpt-4o})
      (.then util/spprint)
      (.then println)))

(defn app []
  (let [all-models (keys (get allem.core/config :models))
        {:keys [text answer model]
         :or {model (first all-models)}} @!state]
    [:div [:h1 "Hello my friend!"]
     [:div [:p "please chat:"]
      [:select
       {:on-change #(swap! !state assoc :model (keyword (.-value (.-target %))))}
       (for [p all-models]
         [:option {:id (name p)
                   :selected (= model p)}
          (name p)])]
      [:p
       [:textarea
        {:value text
         :rows 10
         :cols 100
         :on-change
         (fn [e]
           (swap! !state assoc :text (.-value (.-target e))))}]]
      [:p
       [:button {:on-click #(-> (do-request! {:message text
                                              :model   model})
                                (.then (fn [v]
                                         (swap! !state assoc :answer v))))}
        "submit"]]
      [:p
       (some-> answer markdown/md'->hiccup)]]]))



(defn ^:dev/after-load main []
  (.render root (r/as-element [app])))
