(ns allem.core
  (:require
    [allem.util :as util]
    [clojure.edn :as edn]
    [clojure.string]
    [allem.config]
    #?(:cljs [shadow.resource :as rc])))

(defn throw-on-error [{:keys [status body]
                       :as response}]
  (if (>= status 400)
    (throw (ex-info (str "HTTP Error: " status "\n" body)
                    {:status status
                     :body   body
                     :type   :http-error}))
    response))

#_(defn request-with-throw-on-error [& args]
    (-> @(apply client/request args)
        throw-on-error))



;; ollama serve

(defn message->content [{:keys [upload-fn text-fn]} m]
  (cond
    (string? m)
    (text-fn m)

    (map? m)
    (let [{:keys [ base64 type text]} m]
      (if (util/is-text-mimetype? type)
        (text-fn text)
        (do
          (when-not upload-fn
            (throw (ex-info (str "Non-text file uploads are only supported when upload-fn is available. ") nil)))
          (upload-fn {:mime-type type :base64-data base64 :text text}))))

    :else (throw (ex-info (str "unknown content" m) {:m m}))))


(do
  (defn nil-key [m]
    (-> (for [[k v] m
              :when (nil? v)]
          k)
        util/single))
  (nil-key {:role "user"
            :content nil}))

(do
  (defn structure-content
    [{:keys [level1 level2]} content]
    (assoc level1
      (nil-key level1)
      [(assoc level2
         (nil-key level2)
         content)]))
  (structure-content
    {:level1 {:messages nil}
     :level2 {:role "user"
              :content nil}}
    ["foo" "bar"]))

(def config
  (merge-with merge
              (-> #?(:cljs (rc/inline "./config.edn")
                     :clj (slurp "./config.edn"))
                  edn/read-string)
              #?(:dev-config (-> #?(:cljs (rc/inline "./config.edn")
                                    :clj (slurp "./config.edn"))
                                 edn/read-string))))

(defn make-config [{:keys [model provider api-keys]}]
  (let [provider (or provider
                     (-> (get-in config [:models model])
                         first))
        _ (assert provider model)
        {:keys [models]
         :as   provider-config} (get-in config [:providers provider])
        model-config (get models model)
        config-fns (-> (get allem.config/functions provider)
                       allem.config/normalize-config)
        {:keys [model-name use-latest? body-params]
         :or {model-name (name model)}} (merge config model-config)]
    (merge
      (dissoc provider-config :models)
      config-fns
      (cond-> {:model        (cond-> model-name
                                     use-latest? (str "-latest"))
               :api-key      (get api-keys provider)
               :provider     provider}
              body-params (assoc :body-params body-params)))))

(defn apply-config
  [{:keys [messages] :as config}]
  (let [{:keys [model headers-fn url-fn body-params url]
         :as   config} config
        url-fn (or url-fn (constantly url))]
    (merge
      config
      {:url     (url-fn config)
       :headers (merge
                  {"content-type" "application/json"}
                  (headers-fn config))
       :body    (merge {:model model}
                       (structure-content config
                                          (for [m messages]
                                            (message->content config m)))
                       body-params)})))
(comment
  (apply-config
    (merge
      (make-config {:model   :grok-4
                    :api-key "fake-key-abcd"})

      {:messages ["what's in this image?"]})))

(comment
  (http/send! client
              {:method :get
               :url "https://example.com"}))