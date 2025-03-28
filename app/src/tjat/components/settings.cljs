(ns tjat.components.settings
  (:require [tjat.ui :as ui]
            [tjat.db :as db]
            [tjat.algolia :as a]
            ["@instantdb/core" :as instantdb]
            ["algoliasearch" :as algolia]))

(defn settings-panel 
  "Panel for configuring InstantDB and Algolia"
  [{:keys [instantdb-app-id algolia chats]}
   {:keys [on-instantdb-change on-algolia-id-change on-algolia-key-change on-algolia-import]}]
  [:details {:open false}
   [:summary "Settings"]
   [:div {:style {:display :flex}}
    [:a {:href   "https://www.instantdb.com/dash"
         :target "_blank"}
     "InstantDB"]
    " app-id: "
    [ui/edit-field {:on-save on-instantdb-change
                    :value   instantdb-app-id}]]
   [:div
    [:div {:style {:display :flex}}
     [:a {:href "https://dashboard.algolia.com/"
          :target "_blank"}
      "Algolia"]
     " App-id: "
     [ui/edit-field {:secret? false
                     :on-save on-algolia-id-change
                     :value   (:app-id algolia)}]]
    [:div {:style {:display :flex}}
     "Algolia API key (write): "
     [ui/edit-field {:on-save on-algolia-key-change
                     :value   (:api-key algolia)}]]
    [:button {:on-click on-algolia-import
              :disabled (or (empty? (:api-key algolia))
                           (empty? (:app-id algolia)))}
     "Import to Algolia"]]])