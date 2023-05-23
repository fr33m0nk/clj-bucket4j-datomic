# fr33m0nk/clj-bucket4j-datomic [![Clojars Project](https://img.shields.io/clojars/v/net.clojars.fr33m0nk/clj-bucket4j-datomic.svg)](https://clojars.org/net.clojars.fr33m0nk/clj-bucket4j-datomic)

Distributed Bucket4J implementation for Datomic Database 

## How to find this library?

Add the following to your project dependencies:

- CLI/deps.edn dependency information
```
net.clojars.fr33m0nk/clj-bucket4j-datomic {:mvn/version "0.1.0"}
```
- Leningen/Boot
```
[net.clojars.fr33m0nk/clj-bucket4j-datomic "0.1.0"]
```
- Maven
```xml
<dependency>
  <groupId>net.clojars.fr33m0nk</groupId>
  <artifactId>clj-bucket4j-datomic</artifactId>
  <version>0.1.0</version>
</dependency>
```
- Gradle
```groovy
implementation("net.clojars.fr33m0nk:clj-bucket4j-datomic:0.1.0")
```
## Usage

### Add [Datomic peer dependency](https://mvnrepository.com/artifact/com.datomic/peer) in you project if not present.

### **Prior to using below functions, it is necessary to execute [these Datomic migrations](https://github.com/fr33m0nk/clj-bucket4j-datomic/blob/master/src/fr33m0nk/datomic_schema.clj)**

#### All functions are available through the [`fr33m0nk.clj-bucket4j-datomic`](https://github.com/fr33m0nk/clj-bucket4j-datomic/blob/master/src/fr33m0nk/clj_bucket4j_datomic.clj) namespace

##### Important Functions:

| Name                                                      | Description                                                                                                                                                                      |
|-----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `fr33m0nk.clj-bucket4j-datomic/->datomic-proxy-manager`   | Returns Distributed Bucket4J Proxy Manager for Datomic                                                                                                                           |
| `fr33m0nk.clj-bucket4j-datomic/add-distributed-bucket`    | Adds a distributed bucket to Datomic. NOOP if bucket already exists.<br/> Returns the instance of bucket                                                                         |
| `fr33m0nk.clj-bucket4j-datomic/remove-distributed-bucket` | Removes a distributed bucket. <br/> Permanent removal is determined by the RecoveryStrategy chosen while creating the bucket.<br/> Default strategy is to always RestoreOnDelete |

## Example:
#### as a [distributed throttler](https://bucket4j.com/8.3.0/toc.html#using-bucket-as-throttler)
> Suppose you need to have a fresh exchange rate between dollars and euros. To get the rate you continuously poll the third-party provider, and by contract with the provider you should poll not often than 100 times per 1 minute, else provider will block your IP:
```clojure
(require '[datomic.api :as d] '[fr33m0nk.clj-bucket4j :as b4j] '[fr33m0nk.datomic-schema :refer [b4j-schema]] '[fr33m0nk.clj-bucket4j-datomic :as b4j-datomic])
(import '(io.github.bucket4j.distributed.proxy ClientSideConfig))

(def datomic-conn (return-datomic-connection))

;; Execute Datomic migrations for supporting Distributed Bucket4J
@(d/transact datomic-conn b4j-schema)

;; Instance of datomic-proxy-manager
;; For most cases `(ClientSideConfig/getDefault)` is enough
;; Look into documentation of ClientSideConfig to figure right scenarios to customize it
(def datomic-proxy-manager (b4j-datomic/->datomic-proxy-manager datomic-conn (ClientSideConfig/getDefault)))

;; Bucket configuration allowing 100 hits in 60000 ms (1 minute)
(def bucket-configuration (-> (b4j/bucket-configuration-builder)
                              (b4j/add-limit (b4j/simple-bandwidth 100 60000))
                              (b4j/build)))

;; Adds a distributed bucket to Datomic
(def distributed-bucket (b4j-datomic/add-distributed-bucket proxy-manager "test-bucket-1" bucket-configuration))

(def exchange-rates (atom 0.0))

;; do polling in infinite loop
(while true
  ;; Consume a token from the token bucket.
  ;; Depending on the availability of Token, `b4j/try-consume` returns true or false.
  (when (b4j/try-consume distributed-bucket 1)
    (swap! exchange-rate #(identity %2) (poll-exchange-rate))))

```

#### More detailed usages of below functions can be found in [tests](https://github.com/fr33m0nk/clj-bucket4j-datomic/blob/master/test/fr33m0nk/clj_bucket4j_datomic_test.clj).

## License

Copyright © 2023 Prashant Sinha

Distributed under the MIT License.
