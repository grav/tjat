(ns tjat.db
  (:require ["@instantdb/core"
             :as instantdb]
            [allem.util :as util]))

;; https://www.instantdb.com/docs/start-vanilla
;; we don't need no hooks!

(defn init-instant-db [{:keys [app-id subscriptions on-success on-error !state]
                        :or   {on-error   #(js/console.warn (.-error %))}}]
  (let [on-success (or on-success
                       (and !state
                            (fn [r]
                              (swap! !state merge (js->clj (.-data r)
                                                           :keywordize-keys true))))
                       (throw (ex-info "Neither on-success nor !state was given to init-instant-db!"
                                       {})))

        db (instantdb/init #js{:appId app-id})
        unsubscribe-fns (.subscribeQuery db (clj->js subscriptions)
                                         (fn [r]
                                           (if (.-error r)
                                             (on-error r)
                                             (on-success r))))]
    {:db          db
     :unsubscribe unsubscribe-fns}))


