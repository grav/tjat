(ns tjat.db
  (:require ["@instantdb/core"
             :as instantdb]
            [allem.util :as util]))

;; https://www.instantdb.com/docs/start-vanilla
;; we don't need no hooks!

(defn init-instant-db [{:keys [app-id subscriptions on-success on-error !state]
                        :or   {on-error #(js/console.log (.-error %))}}]
  (let [on-success (or on-success
                       (and !state
                            (fn [r]
                              (swap! !state merge (js->clj (.-data r)
                                                           :keywordize-keys true))))
                       (throw (ex-info "Neither on-success nor !state was given to init-instant-db!"
                                       {})))

        db (try
             (instantdb/init #js{:appId app-id})
             (catch js/Error e
               (js/alert e)))
        unsubscribe-fn ^js/Function (.subscribeQuery db (clj->js subscriptions)
                                                     (fn [r]
                                                       (if (.-error r)
                                                         (on-error r)
                                                         (on-success r))))]
    {:db          db
     :unsubscribe (fn []
                    (when !state
                      (swap! !state #(apply dissoc % (keys subscriptions))))
                    (unsubscribe-fn))}))


