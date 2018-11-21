(ns onyx.plugin.output-test
  (:require [clojure.core.async :refer [<!! go pipe close! >!!]]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]
            [onyx.test-helper :refer [with-test-env]]
            [onyx.job :refer [add-task]]
            [onyx.kafka.helpers :as h]
            [onyx.tasks.kafka :refer [producer]]
            [onyx.tasks.core-async :as core-async]
            [onyx.plugin.core-async :refer [get-core-async-channels]]
            [onyx.plugin.test-utils :as test-utils]
            [onyx.plugin.kafka]
            [onyx.api]
            [taoensso.timbre :as log]))

(defn build-job [zk-address topic batch-size batch-timeout]
  (let [batch-settings {:onyx/batch-size batch-size
                        :onyx/batch-timeout batch-timeout}
        base-job (merge {:workflow   [[:in :identity]
                                      [:identity :write-messages]]
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
        (add-task (core-async/input :in batch-settings))
        (add-task (producer :write-messages
                                (merge {:kafka/topic topic
                                        :kafka/zookeeper zk-address
                                        :kafka/serializer-fn :onyx.tasks.kafka/serialize-message-edn
                                        :kafka/key-serializer-fn :onyx.tasks.kafka/serialize-message-edn
                                        :kafka/request-size 307200}
                                       batch-settings))))))

(defn- decompress
  [v]
  (when v
    (read-string (String. v "UTF-8"))))

(defn- prepare-messages
  [coll & extra-keys]
  (log/infof "Preparing %d messages..." (count coll))
  (->> coll
       (sort-by (comp :n :value))
       (map #(select-keys % (into [:key :partition :topic :value] extra-keys)))))

(deftest kafka-output-test
  (let [test-topic (str "onyx-test-" (java.util.UUID/randomUUID))
        other-test-topic (str "onyx-test-other-" (java.util.UUID/randomUUID))
        timestamp-test-topic (str "onyx-test-other-" (java.util.UUID/randomUUID))
        test-timestamp (System/currentTimeMillis)
        {:keys [test-config env-config peer-config]} (onyx.plugin.test-utils/read-config)
        tenancy-id (str (java.util.UUID/randomUUID))
        env-config (assoc env-config :onyx/tenancy-id tenancy-id)
        peer-config (assoc peer-config :onyx/tenancy-id tenancy-id)
        zk-address (get-in peer-config [:zookeeper/address])
        job (build-job zk-address test-topic 10 1000)
        bootstrap-servers (:kafka-bootstrap test-config)
        {:keys [in]} (get-core-async-channels job)
        test-data [{:key 1 :message {:n 0}}
                   {:message {:n 1}}
                   {:key "tarein" :message {:n 2}}
                   {:message {:n 3} :topic other-test-topic}
                   {:message {:n 4} :topic timestamp-test-topic :timestamp test-timestamp}]]
      (with-test-env [test-env [4 env-config peer-config]]
        (onyx.test-helper/validate-enough-peers! test-env job)
        (h/create-topic! zk-address test-topic 1 1)
        (h/create-topic! zk-address other-test-topic 1 1)
        (h/create-topic! zk-address timestamp-test-topic 1 1)
        (run! #(>!! in %) test-data)
        (close! in)
        (->> (onyx.api/submit-job peer-config job)
             :job-id
             (onyx.test-helper/feedback-exception! peer-config))
        (testing "routing to default topic"
          (log/info "Waiting on messages in" test-topic)
          (let [msgs (prepare-messages
                      (h/take-now bootstrap-servers test-topic decompress 15000))]
            (is (= [test-topic] (->> msgs (map :topic) distinct)))
            (is (= [{:key 1 :value {:n 0} :partition 0}
                    {:key nil :value {:n 1} :partition 0}
                    {:key "tarein" :value {:n 2} :partition 0}]
                   (map #(dissoc % :topic) msgs)))))
        (testing "overriding the topic"
          (log/info "Waiting on messages in" other-test-topic)
          (is (= [{:key nil :value {:n 3} :partition 0 :topic other-test-topic}]
                 (prepare-messages
                  (h/take-now bootstrap-servers other-test-topic decompress)))))
        (testing "overriding the timestamp"
          (log/info "Waiting on messages in" timestamp-test-topic)
          (is (= [{:key nil :value {:n 4} :partition 0 :topic timestamp-test-topic :timestamp test-timestamp}]
                 (prepare-messages (h/take-now bootstrap-servers timestamp-test-topic decompress) :timestamp)))))))
