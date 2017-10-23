(ns duct.server.http.aleph-test
  (:import java.net.ConnectException)
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [duct.core :as duct]
            [duct.logger :as logger]
            [duct.server.http.aleph :as aleph]
            [integrant.core :as ig]))

(defrecord TestLogger [logs]
  logger/Logger
  (-log [_ level ns-str file line id event data]
    (swap! logs conj [event data])))

(duct/load-hierarchy)

(deftest key-test
  (is (isa? :duct.server.http/aleph :duct.server/http)))

(deftest init-and-halt-test
  (let [response {:status 200 :headers {} :body "test"}
        logger   (->TestLogger (atom []))
        handler  (constantly response)
        config   {:duct.server.http/aleph {:port 3400, :handler handler, :logger logger}}]

    (testing "server starts"
      (let [system (ig/init config)]
        (try
          (let [response (http/get "http://127.0.0.1:3400/")]
            (is (= (:status response) 200))
            (is (= (:body response) "test")))
          (finally
            (ig/halt! system)))))

    (testing "server stops"
      (is (thrown? ConnectException (http/get "http://127.0.0.1:3400/"))))

    (testing "start and stop were logged"
      (is (= @(:logs logger)
             [[::aleph/starting-server {:port 3400}]
              [::aleph/stopping-server nil]])))

    (testing "halt is idempotent"
      (let [system (ig/init config)]
        (ig/halt! system)
        (ig/halt! system)
        (is (thrown? ConnectException (http/get "http://127.0.0.1:3400/")))))))

(deftest resume-and-suspend-test
  (let [response1 {:status 200 :headers {} :body "foo"}
        response2 {:status 200 :headers {} :body "bar"}
        config1   {:duct.server.http/aleph {:port 3400, :handler (constantly response1)}}
        config2   {:duct.server.http/aleph {:port 3400, :handler (constantly response2)}}]

    (testing "suspend and resume"
      (let [system1  (doto (ig/init config1) ig/suspend!)
            response (future (http/get "http://127.0.0.1:3400/"))
            system2  (ig/resume config2 system1)]
        (try
          (is (identical? (-> system1 :duct.server.http/aleph :handler)
                          (-> system2 :duct.server.http/aleph :handler)))
          (is (identical? (-> system1 :duct.server.http/aleph :server)
                          (-> system2 :duct.server.http/aleph :server)))
          (is (= (:status @response) 200))
          (is (= (:body @response) "bar"))
          (finally
            (ig/halt! system1)
            (ig/halt! system2)))))

    (testing "suspend and resume with different config"
      (let [system1  (doto (ig/init config1) ig/suspend!)
            config2' (assoc-in config2 [:duct.server.http/aleph :port] 3401)
            system2  (ig/resume config2' system1)]
        (try
          (let [response (http/get "http://127.0.0.1:3401/")]
            (is (= (:status response) 200))
            (is (= (:body response) "bar")))
          (finally
            (ig/halt! system1)
            (ig/halt! system2)))))

    (testing "suspend and resume with extra config"
      (let [system1 (doto (ig/init {}) ig/suspend!)
            system2 (ig/resume config2 system1)]
        (try
          (let [response (http/get "http://127.0.0.1:3400/")]
            (is (= (:status response) 200))
            (is (= (:body response) "bar")))
          (finally
            (ig/halt! system2)))))

    (testing "suspend and result with missing config"
      (let [system1  (doto (ig/init config1) ig/suspend!)
            system2  (ig/resume {} system1)]
        (is (= system2 {}))))

    (testing "logger is replaced"
      (let [logs1   (atom [])
            logs2   (atom [])
            config1 (assoc-in config1 [:duct.server.http/aleph :logger] (->TestLogger logs1))
            config2 (assoc-in config2 [:duct.server.http/aleph :logger] (->TestLogger logs2))
            system1 (doto (ig/init config1) ig/suspend!)
            system2 (ig/resume config2 system1)]
        (ig/halt! system2)
        (is (= @logs1 [[::aleph/starting-server {:port 3400}]]))
        (is (= @logs2 [[::aleph/stopping-server nil]]))
        (ig/halt! system1)))))
