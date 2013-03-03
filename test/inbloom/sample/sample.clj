(ns inbloom.sample.sample
  (:use [slingshot.slingshot :only [throw+ try+]]
        inbloom.core
        ring.adapter.jetty))

(defn handler
  [request]
  (try+
    (let [me (follow-link "self" (home))]
      {:status 200
       :body (str "Hello world\n" me)})))

(run-jetty 
  (in-bloom
    handler
    :client-id "<YOUR_CLIENT_ID_HERE>" :secret "<YOUR_CLIENT_SECRET_HERE>"
    :app-server "http://localhost:1337")
  {:port 1337})
