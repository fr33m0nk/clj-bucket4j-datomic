(ns fr33m0nk.clj-bucket4j-datomic-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [datomic.api :as d]
    [fr33m0nk.datomic-schema :refer [b4j-schema]]
    [fr33m0nk.clj-bucket4j-datomic :as b4j-datomic])
  (:import
    (fr33m0nk.clj_bucket4j_datomic B4JDatomicTransaction)
    (io.github.bucket4j Bandwidth BucketConfiguration)
    (io.github.bucket4j.distributed.proxy BucketNotFoundException ClientSideConfig RecoveryStrategy)
    (io.github.bucket4j.distributed.proxy.generic.compare_and_swap AbstractCompareAndSwapBasedProxyManager)
    (java.time Duration)
    (java.util.concurrent CountDownLatch)
    (java.util.function Function Supplier)))

(defmacro test-harness
  [datomic-conn & body]
  `(let [uri# "datomic:mem://bucket4j"
         _# (d/create-database uri#)
         ~datomic-conn (d/connect uri#)
         _# @(d/transact ~datomic-conn b4j-schema)]
     ~@body
     (d/release ~datomic-conn)
     (d/delete-database uri#)))

(deftest datomic-migration-test
  (testing "migrations are executed successfully"
    (test-harness
      datomic-conn
      (is (number? (:db/id (d/entity (d/db datomic-conn) :bucket/id))))
      (is (number? (:db/id (d/entity (d/db datomic-conn) :bucket/state)))))))


(deftest datomic-proxy-manager-test
  (test-harness
    datomic-conn
    (let [proxy-manager (b4j-datomic/->datomic-proxy-manager datomic-conn (ClientSideConfig/getDefault))]
      (testing "returns an instance of AbstractCompareAndSwapBasedProxyManager"
        (is (instance? AbstractCompareAndSwapBasedProxyManager proxy-manager)))

      (testing "returns non-null transaction on allocate transaction"
        (is (instance? B4JDatomicTransaction (b4j-datomic/begin-compare-and-swap-operation proxy-manager "test-key"))))

      (let [bucket-configuration (fn [capacity interval-ms]
                                   (-> (BucketConfiguration/builder)
                                       (.addLimit (Bandwidth/simple capacity (Duration/ofMillis interval-ms)))
                                       (.build)))
            bucket-1 (-> proxy-manager
                         (.builder)
                         (.build "test-bucket-1" (bucket-configuration 4 14400000)))
            bucket-2 (-> proxy-manager
                         (.builder)
                         (.build "test-bucket-2" (bucket-configuration 8 60000)))]
        (testing "adds a distributed bucket"
          (is (= 8 (.getAvailableTokens bucket-2)))
          (is (= 4 (.getAvailableTokens bucket-1)))
          (is (= 2 (->> (d/datoms (d/db datomic-conn) :avet :bucket/id)
                        (keep :e)
                        count)))
          (is (= {:capacity 4
                  :class io.github.bucket4j.Bandwidth
                  :gready true
                  :id nil
                  :initialTokens 4
                  :intervallyAligned false
                  :refillIntervally false
                  :refillPeriodNanos 14400000000000
                  :refillTokens 4
                  :timeOfFirstRefillMillis -9223372036854775808
                  :useAdaptiveInitialTokens false}
                 (some-> (.getProxyConfiguration proxy-manager "test-bucket-1")
                         (.orElse nil)
                         (.getBandwidths)
                         (first)
                         (bean))))
          (is (= {:capacity 8
                  :class io.github.bucket4j.Bandwidth
                  :gready true
                  :id nil
                  :initialTokens 8
                  :intervallyAligned false
                  :refillIntervally false
                  :refillPeriodNanos 60000000000
                  :refillTokens 8
                  :timeOfFirstRefillMillis -9223372036854775808
                  :useAdaptiveInitialTokens false}
                 (some-> (.getProxyConfiguration proxy-manager "test-bucket-2")
                         (.orElse nil)
                         (.getBandwidths)
                         (first)
                         (bean)))))

        (testing "removes proxy for provided bucket id"
          (is (some? (-> proxy-manager
                         (.getProxyConfiguration "test-bucket-1")
                         (.orElse nil))))
          (.removeProxy proxy-manager "test-bucket-1")
          (is (nil? (-> proxy-manager
                        (.getProxyConfiguration "test-bucket-1")
                        (.orElse nil)))
              "This is only temporary. Proxy Manager would restore the bucket as default RecoveryStrategy is to reconstruct bucket")
          (is (some? (-> proxy-manager
                         (.getProxyConfiguration "test-bucket-2")
                         (.orElse nil))))
          (is (= 1 (->> (d/datoms (d/db datomic-conn) :avet :bucket/id)
                        (keep :e)
                        count))))

        (testing "recovers from a crash using default reconstruct RecoveryStrategy"
          (is (true? (.tryConsume bucket-2 1)))
          ;; simulate a crash
          (.removeProxy proxy-manager "test-bucket-2")
          (is (true? (.tryConsume bucket-2 1))))

        (testing "recovers from a crash and throws exception using ThrowExceptionRecoveryStrategy"
          (let [bucket-3 (-> proxy-manager
                             (.builder)
                             (.withRecoveryStrategy RecoveryStrategy/THROW_BUCKET_NOT_FOUND_EXCEPTION)
                             (.build "test-bucket-3"
                                     (bucket-configuration 8 60000)))]
            (is (true? (.tryConsume bucket-3 1)))
            ;; simulate a crash
            (b4j-datomic/remove-distributed-bucket proxy-manager "test-bucket-3")
            (is (instance? BucketNotFoundException (try
                                                     (.tryConsume bucket-3 1)
                                                     (catch Exception ex
                                                       ex))))))

        (testing "returns parallel initialized buckets and buckets are thread safe"
          (let [configuration (-> (BucketConfiguration/builder)
                                  (.addLimit (reify Function
                                                (apply [_ limit]
                                                  (.capacity limit 10)
                                                  (.refillIntervally limit 1 (Duration/ofMillis 60000)))))
                                  (.build))
                parallelism (int 4)
                start-latch (CountDownLatch. parallelism)
                stop-latch (CountDownLatch. parallelism)]
            (doseq [_ (range 4)]
              (-> (Thread. ^Runnable (fn []
                                       (.countDown start-latch)
                                       (try
                                         (.await start-latch)
                                         (catch InterruptedException ex
                                           (.printStackTrace ex)))
                                       (try
                                         (let [bucket
                                               (-> proxy-manager
                                                   (.builder)
                                                   (.build "parallel-test-bucket"
                                                           (reify Supplier (get [_] configuration))))]
                                           (.tryConsume bucket 1))
                                         (catch Exception ex
                                           (.printStackTrace ex))
                                         (finally
                                           (.countDown stop-latch)))))
                  (.start)))
            (.await stop-latch)
            (let [bucket
                  (-> proxy-manager
                      (.builder)
                      (.build "parallel-test-bucket" (reify Supplier (get [_] configuration))))]
              (is (= (- 10 parallelism) (.getAvailableTokens bucket))))))))))
