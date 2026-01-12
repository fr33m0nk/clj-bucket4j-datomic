(ns fr33m0nk.clj-bucket4j-datomic
  (:require
    [datomic.api :as d]
    [com.rpl.proxy-plus :refer [proxy+]])
  (:import
    (io.github.bucket4j BucketConfiguration)
    (io.github.bucket4j.distributed.proxy ClientSideConfig RecoveryStrategy)
    (io.github.bucket4j.distributed.proxy.generic.compare_and_swap AbstractCompareAndSwapBasedProxyManager CompareAndSwapOperation)
    (java.util Optional)
    (java.util.concurrent ExecutionException TimeUnit)
    (java.util.function Function Supplier)
    (org.slf4j Logger LoggerFactory)))

(def logger (LoggerFactory/getLogger "fr33m0nk.clj-bucket4j-datomic"))

(deftype B4JDatomicTransaction
  [conn bucket-id]
  CompareAndSwapOperation
  (compareAndSwap [_ original-data new-data _new-state timeout-nanos]
    (try
      (if-let [timeout-ms (-> timeout-nanos
                              (.map (reify Function
                                      (apply [_ time-out-nanos]
                                        (.toMillis TimeUnit/NANOSECONDS time-out-nanos))))
                              (.orElse nil))]
        (deref (d/transact conn [[:bucket/transact bucket-id original-data new-data]])
               timeout-ms false)
        (do @(d/transact conn [[:bucket/transact bucket-id original-data new-data]])
            true))
      (catch ExecutionException ex
        (let [inner-exception (.getCause ^ExecutionException ex)
              cause (-> inner-exception ex-data :cognitect.anomalies/category)]
          (if (= cause :cognitect.anomalies/conflict)
            false
            (do
              (.error ^Logger logger (format "Something went wrong during compareAndSwap of Datomic proxy state for key: %s" bucket-id) inner-exception)
              (throw inner-exception)))))
      (catch Exception ex
        (.error ^Logger logger (format "Something went wrong during compareAndSwap of Datomic proxy state for key: %s" bucket-id) ex)
        (throw ex))))
  (getStateData [_ timeout-nanos]
    (let [bucket (if-let [timeout-ms (-> timeout-nanos
                                         (.map (reify Function
                                                 (apply [_ time-out-nanos]
                                                   (.toMillis TimeUnit/NANOSECONDS time-out-nanos))))
                                         (.orElse nil))]
                   (d/q '[:find (pull ?e [:bucket/id :bucket/state]) .
                          :in $ ?e]
                        (d/db conn)
                        [:bucket/id bucket-id]
                        {:timeout timeout-ms})
                   (d/pull (d/db conn) '[:bucket/id :bucket/state] [:bucket/id bucket-id]))]
      (if (empty? bucket)
        (Optional/empty)
        (Optional/of (:bucket/state bucket))))))

(defn ->datomic-proxy-manager
  [conn ^ClientSideConfig client-side-config]
  (proxy+ [client-side-config]
          AbstractCompareAndSwapBasedProxyManager
    (beginCompareAndSwapOperation [this bucket-id]
                                  (B4JDatomicTransaction. conn bucket-id))
    (removeProxy [this bucket-id]
                 (try
                   @(d/transact conn [[:db/retractEntity [:bucket/id bucket-id]]])
                   nil
                   (catch ExecutionException ex
                     (let [inner-exception (.getCause ^ExecutionException ex)
                           cause (-> inner-exception ex-data :cognitect.anomalies/category)]
                       (if (= cause :cognitect.anomalies/conflict)
                         false
                         (do
                           (.error ^Logger logger (format "Something went wrong while removing Datomic proxy for key: %s" bucket-id) inner-exception)
                           (throw inner-exception)))))
                   (catch Exception ex
                     (.error ^Logger logger (format "Something went wrong while removing Datomic proxy for key: %s" bucket-id) ex)
                     (throw ex))))
    (beginAsyncCompareAndSwapOperation [this bucket-id]
                                       (throw (UnsupportedOperationException.)))
    (removeAsync [this bucket-id]
                 (throw (UnsupportedOperationException.)))
    (isAsyncModeSupported [this]
                          false)))

(defprotocol IDatomicProxyManager
  (begin-compare-and-swap-operation [datomic-proxy-manager ^String bucket-id])
  (remove-distributed-bucket [datomic-proxy-manager ^String bucket-id])
  (add-distributed-bucket
    [datomic-proxy-manager ^String bucket-id ^BucketConfiguration bucket-configuration]
    [datomic-proxy-manager ^String bucket-id ^BucketConfiguration bucket-configuration ^RecoveryStrategy recovery-strategy]
    "Adds a distributed bucket in Datomic with supplied bucket-id and configuration"))

(extend-type AbstractCompareAndSwapBasedProxyManager
  IDatomicProxyManager
  (begin-compare-and-swap-operation [this ^String bucket-id]
    (.beginCompareAndSwapOperation this bucket-id))
  (remove-distributed-bucket [this ^String bucket-id]
    (.removeProxy this bucket-id))
  (add-distributed-bucket
    ([this ^String bucket-id ^BucketConfiguration bucket-configuration]
     (-> (.builder this)
         (.build bucket-id (reify Supplier (get [_] bucket-configuration)))))
    ([this ^String bucket-id ^BucketConfiguration bucket-configuration ^RecoveryStrategy recovery-strategy]
     (-> (.builder this)
         (.withRecoveryStrategy recovery-strategy)
         (.build bucket-id (reify Supplier (get [_] bucket-configuration)))))))
