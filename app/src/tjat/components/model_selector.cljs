(ns tjat.components.model-selector
  (:require [tjat.ui :as ui]))

(defn model-selector 
  "Component for selecting AI model and entering API keys"
  [{:keys [model all-models provider api-keys]}
   {:keys [on-model-change on-api-key-save]}]
  [:div
   "Model: "
   [:select
    {:value     (name model)
     :on-change (fn [e]
                  (let [model (.-value (.-target e))]
                    (js/localStorage.setItem "tjat-model" model)
                    (on-model-change (keyword model))))}
    (for [p all-models]
      ^{:key (name p)}
      [:option {:id (name p)}
       (name p)])]
   (when provider
     [:div [:p "Provider: "
            [:b (name provider)]]
      [:div {:style {:display :flex}}
       [:div "Api key: "]
       [ui/edit-field {:on-save      (fn [k]
                                       (let [api-keys (if (seq k)
                                                       (merge api-keys
                                                              {provider k})
                                                       (dissoc api-keys provider))]
                                         (on-api-key-save api-keys)
                                         (js/localStorage.setItem "tjat-api-keys" (pr-str api-keys))))
                       :value (get api-keys provider)}]]])])