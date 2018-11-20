(ns onyx.plugin.input-seek-latest-test
  (:require [clojure.core.async :refer [<!! go pipe]]
            [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [aero.core :refer [read-config]]
            [onyx.test-helper :refer [with-test-env]]
            [onyx.job :refer [add-task]]
            [onyx.kafka.helpers :as h]
            [onyx.tasks.kafka :refer [consumer]]
            [onyx.tasks.core-async :as core-async]
            [onyx.plugin.core-async :refer [get-core-async-channels]]
            [onyx.plugin.test-utils :as test-utils]
            [onyx.plugin.kafka]
            [onyx.api]))

(defn build-job [zk-address topic batch-size batch-timeout]
  (let [batch-settings {:onyx/batch-size batch-size :onyx/batch-timeout batch-timeout}
        base-job (merge {:workflow [[:read-messages :identity]
                                    [:identity :out]]
                         :catalog [(merge {:onyx/name :identity
                                           :onyx/fn :clojure.core/identity
                                           :onyx/type :function}
                                          batch-settings)]
                         :lifecycles []
                         :windows []
                         :triggers []
                         :flow-conditions []
                         :task-scheduler :onyx.task-scheduler/balanced})]
    (-> base-job
        (add-task (consumer :read-messages
                            (merge {:kafka/topic topic
                                    :kafka/group-id "onyx-consumer"
                                    :kafka/zookeeper zk-address
                                    :kafka/offset-reset :latest
                                    :kafka/deserializer-fn :onyx.tasks.kafka/deserialize-message-edn
                                    :onyx/min-peers 2
                                    :onyx/max-peers 2}
                                   batch-settings)))
        (add-task (core-async/output :out batch-settings)))))

(defn write-messages
  "Use a custom version of mock-kafka as opposed to the one in test-utils
  because we need to spawn 2 producers in order to write to each partition"
  [topic zookeeper bootstrap-servers]
  (let [producer-config {"bootstrap.servers" bootstrap-servers
                         "key.serializer" (h/byte-array-serializer-name)
                         "value.serializer" (h/byte-array-serializer-name)}]
    (with-open [producer1 (h/build-producer producer-config)]
      (with-open [producer2 (h/build-producer producer-config)]
        (doseq [x (range 3)] ;0 1 2
          (h/send-sync! producer1 topic nil nil (.getBytes (pr-str {:n x}))))
        (doseq [x (range 3)] ;3 4 5
          (h/send-sync! producer2 topic nil nil (.getBytes (pr-str {:n (+ 3 x)}))))))))

(deftest kafka-input-test
  (let [test-topic (str "onyx-test-" (java.util.UUID/randomUUID))
        _ (println "Using topic" test-topic)
        {:keys [env-config test-config peer-config]} (read-config (clojure.java.io/resource "config.edn")
                                                                  {:profile :test})
        tenancy-id (str (java.util.UUID/randomUUID))
        env-config (assoc env-config :onyx/tenancy-id tenancy-id)
        peer-config (assoc peer-config :onyx/tenancy-id tenancy-id)
        zk-address (get-in peer-config [:zookeeper/address])
        job (build-job zk-address test-topic 10 1000)
        {:keys [out read-messages]} (get-core-async-channels job)]
    (with-test-env [test-env [4 env-config peer-config]]
      (onyx.test-helper/validate-enough-peers! test-env job)
      (h/create-topic! zk-address test-topic 2 1)
      (write-messages test-topic zk-address (:kafka-bootstrap test-config))
      (let [job-id (:job-id (onyx.api/submit-job peer-config job))]
        (Thread/sleep 2000)
        (write-messages test-topic zk-address (:kafka-bootstrap test-config))
        (is (= 15 (reduce + (mapv :n (onyx.plugin.core-async/take-segments! out 10000)))))
        (onyx.api/kill-job peer-config job-id)
        (Thread/sleep 10000)))))
