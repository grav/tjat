(ns tjat.core
  (:require ["react-dom/client" :as react-dom]
            [allem.util :as util]
            [reagent.core :as r]
            [goog.dom :as gdom]
            [allem.core]
            [httpurr.client :as http]
            [httpurr.client.xhr-alt :refer [client]]
            [tjat.markdown :as markdown]
            ["showdown" :as showdown]
            [tjat.db :as db]
            [tjat.ui :as ui]))

(defonce root (react-dom/createRoot (gdom/getElement "app")))

(defonce !state (r/atom nil))

(defn do-request! [{:keys [message model api-keys]}]
  (let [config (allem.core/make-config
                 {:model    model
                  :api-keys api-keys})
        {:keys [reply-fn headers url body]} (allem.core/apply-config
                                              (assoc config
                                                :message message))]
    (-> (http/send! client {:method  :post
                            :url     url
                            :headers headers
                            :body    (js/JSON.stringify (clj->js body))})
        (.then #(get % :body))
        (.then js/JSON.parse)
        (.then #(js->clj % :keywordize-keys true))
        (.then reply-fn))))

(comment
  (-> (do-request! {:message "yolo"
                    :model   :gpt-4o})
      (.then util/spprint)
      (.then println)))





(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn []
      (let [all-models (->> (get allem.core/config :models)
                            keys
                            sort)
            {:keys [text answer model api-keys loading]
             :or   {model    (first all-models)
                    api-keys api-keys-persisted}} @!state
            {:keys [provider]} (allem.core/make-config {:model model})]
        [:div
         #_[:pre (util/spprint @!state)]
         [:h1 "Tjat!"]
         [:div
          "Model: "
          [:select
           {:value     (name model)
            :on-change #(swap! !state assoc :model (keyword (.-value (.-target %))))}
           (for [p all-models]
             ^{:key (name p)}
             [:option {:id       (name p)}
              (name p)])]
          (when provider
            [:div [:p (str "Provider: ")
                   [:b (name provider)]]
             [:div {:style {:display :flex}}
              [:div "Api key:"]
              [ui/secret-edit-field {:on-save (fn [k]
                                                (let [api-keys (if (seq k)
                                                                 (merge api-keys
                                                                        {provider k})
                                                                 (dissoc api-keys provider))]
                                                  (swap! !state assoc :api-keys api-keys)
                                                  (js/localStorage.setItem "tjat-api-keys" (pr-str api-keys))))
                                     :value        (get api-keys provider)}]]])

          [:p
           [:textarea
            {:value text
             :rows  10
             :cols  100
             :on-change
             (fn [e]
               (swap! !state assoc :text (.-value (.-target e))))}]]
          [:p
           {:style {:height 50}}
           [:button {:on-click #(do
                                  (swap! !state assoc :loading true)
                                  (-> (do-request! {:message  text
                                                    :model    model
                                                    :api-keys api-keys})
                                      (.then (fn [v]
                                               (swap! !state assoc :answer v
                                                      :loading false)))))}
            "submit"]
           (when loading
             [:svg.spinner
              {:width   "20"
               :height  "20"
               :viewBox "0 0 50 50"}
              [:circle.spinner-circle
               {:cx           "25"
                :cy           "25"
                :r            "20"
                :fill         "none"
                :stroke       "#007bff"
                :stroke-width "4"}]])]
          [:p
           {:dangerouslySetInnerHTML {:__html (.makeHtml
                                                ;; https://github.com/showdownjs/showdown?tab=readme-ov-file#valid-options
                                                (doto (showdown/Converter.)
                                                  (.setFlavor "github")) answer)}}]]]))))

#_(defn testit []
    [:div
     [upload/drop-zone]])

(defn db-test []
  [:div
   [db/instantdb-view]])

(defn ^:dev/after-load main []
  (.render root (r/as-element [db-test])))
