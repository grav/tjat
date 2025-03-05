(ns tjat.db
  (:require ["@instantdb/core"
             :as instantdb]))

;; https://www.instantdb.com/docs/start-vanilla
;; we don't need no hooks!

(defn init-instant-db [{:keys [app-id subscriptions on-success on-error !state]
                        :or   {on-error   #(js/console.warn (.-error %))}}]
  (let [on-success (or on-success
                       (and !state
                            (fn [k r]
                              (swap! !state assoc k (js->clj
                                                      (aget (.-data r) (name k))
                                                      :keywordize-keys true))))
                       (throw (ex-info "Neither on-success nor !state was given to init-instant-db!"
                                       {})))

        db (instantdb/init #js{:appId app-id})
        unsubscribe-fns (->> subscriptions
                             (mapv (fn [k]
                                     (.subscribeQuery db (clj->js {k {}})
                                                      (fn [r]
                                                        (println 'sub k)
                                                        (if (.-error r)
                                                          (on-error r)
                                                          (on-success k r)))))))]
    {:db          db
     :unsubscribe (fn []
                    (doseq [f unsubscribe-fns]
                      (f)))}))


