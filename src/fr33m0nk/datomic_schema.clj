(ns fr33m0nk.datomic-schema
  (:require [datomic.api :as d]))

(def b4j-schema
  [#:db{:doc "Bucket ID for identifying bucket"
        :ident :bucket/id
        :valueType :db.type/string
        :cardinality :db.cardinality/one
        :unique :db.unique/identity
        :index true}
   #:db{:doc "State of the bucket in bytes"
        :ident :bucket/state
        :valueType :db.type/bytes
        :cardinality :db.cardinality/one}
   #:db{:ident :bucket/transact
        :fn (d/function
              '{:lang "clojure"
                :doc "Transaction fn for transacting Bucket entity"
                :params [db bucket-id original-data new-data]
                :code (let [bucket-eid [:bucket/id bucket-id]
                            bucket (d/pull db [:bucket/id :bucket/state] bucket-eid)]
                        (if (empty? bucket)
                          [{:bucket/id bucket-id
                            :bucket/state new-data}]
                          [[:db/cas bucket-eid
                            :bucket/state original-data new-data]]))})}])
