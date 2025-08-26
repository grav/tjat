(ns tjat.core
  (:require ["react-dom/client" :refer [createRoot]]
            [allem.util :as util]
            [reagent.core :as r]
            [goog.dom :as gdom]
            [allem.core]
            [httpurr.client :as http]
            [httpurr.client.xhr-alt :refer [client]]
            [tjat.markdown :as markdown]
            ["showdown" :as showdown]))

(defonce root (createRoot (gdom/getElement "app")))

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

(defn api-key-edit []
  (let [!state (r/atom nil)
        !button-ref (atom nil)
        !input-ref (atom nil)]
    (fn [{:keys [value on-save]}]
      (let [{:keys [is-editing? text]} @!state
            text (or text value)]
        [:div
         [:input {:ref #(reset! !input-ref %)
                  :value     (if is-editing?
                               text
                               (if text
                                 (apply str (repeat (count text) (js/String.fromCodePoint 0x25CF)))
                                 "[none]"))
                  :on-change (fn [e]
                               (swap! !state assoc :text (.-value (.-target e))))
                  :on-focus #(swap! !state assoc :is-editing? true)

                  :on-key-down #(when (= "Enter" (.-key %))
                                  (on-save text)
                                  (.blur (.-target %)))
                  :on-blur (fn [e]
                             (when (not= @!button-ref (.-relatedTarget e))
                               (swap! !state assoc :is-editing? false :text nil)))}]

         (when is-editing?
           [:button {:ref         #(reset! !button-ref %)
                     :on-blur     (fn [e]
                                    (when (not= @!input-ref (.-relatedTarget e))
                                      (swap! !state assoc :is-editing? false :text nil)))
                     :on-key-down #(when (= "Escape" (.-key %))
                                     (.blur (.-target %)))
                     :on-click (fn [_]
                                 (on-save text)
                                 (swap! !state assoc :is-editing? false :text nil))}
            "Save"])]))))



(defn app []
  (let [api-keys-persisted (some-> (js/localStorage.getItem "tjat-api-keys")
                                   clojure.edn/read-string)]
    (fn []
      (let [all-models (keys (get allem.core/config :models))
            {:keys [text answer model api-keys]
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
              [api-key-edit {:on-save (fn [k]
                                        (let [api-keys (if (seq k)
                                                         (merge api-keys
                                                                {provider k})
                                                         (dissoc api-keys provider))]
                                          (swap! !state assoc :api-keys api-keys)
                                          (js/localStorage.setItem "tjat-api-keys" (pr-str api-keys))))
                             :value   (get api-keys provider)}]]])

          [:p
           [:textarea
            {:value text
             :rows  10
             :cols  100
             :on-change
             (fn [e]
               (swap! !state assoc :text (.-value (.-target e))))}]]
          [:p
           [:button {:on-click #(-> (do-request! {:message text
                                                  :model   model
                                                  :api-keys api-keys})
                                    (.then (fn [v]
                                             (swap! !state assoc :answer v))))}
            "submit"]]
          [:p
           {:dangerouslySetInnerHTML (clj->js {:__html (.makeHtml (showdown/Converter.
                                                                    ;; https://github.com/showdownjs/showdown?tab=readme-ov-file#valid-options
                                                                    (clj->js {:tables true})) answer)})}

           #_(some-> answer markdown/md'->hiccup)]]]))))

#_(defn testit []
    [:div
     [upload/drop-zone]])

(defn ^:dev/after-load main []
  (.render root (r/as-element [app])))
