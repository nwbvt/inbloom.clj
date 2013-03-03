(ns inbloom.core
  (:require [clj-http.client :as client]
           [clojure.data.json :as json])
  (:use [slingshot.slingshot :only [throw+ try+]]
        [ring.util.response :only [redirect]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.params :only [wrap-params]]))

(def ^:dynamic *token*)

(defn- init!
  "Initialize everything"
  [api-server client-id secret app-server redirect]
  (let [api-server (or api-server "api.sandbox.inbloom.org")
        app-server (or app-server "localhost:3000")
        redirect (or redirect "/oauth")
        redirect-resource (str app-server redirect)]
    (def redirect-path redirect)
    (def api-url (str api-server "/api/rest/v1.1/"))
    (def token-uri (str api-server "/api/oauth/token&client_id=" client-id "&redirect_uri="
                        redirect-resource "&client_secret" secret "&code="))
    (def oauth-login (str api-server "/api/oauth/authorize?response_type=code&client_id="
                          client-id "&redirect_uri=" redirect-resource))))

(defn- make-uri
  "creates the uri
  basically if the resource already starts with the api url, keep it as it is
  otherwise, append the resource to the api url"
  [resource]
  (if (.startsWith resource api-url) resource (str api-url resource)))

(defn- make-get-request
  "make the actual request"
  [resource]
  (let [response (client/get (make-uri resource) {:oauth-token *token*})
        code (:status response)
        body (:body response)]
    (if (= 200 code) body
      (throw+ {:type :request-error :resource resource :body body :code code}))))

(defn get-request
  "Make a request the api"
  [resource]
  (json/read-str (:body (make-get-request resource))))

(defn- logged-in?
  "is the user logged in?"
  [{token :token}]
  (not (nil? token)))

(defn- add-to-session
  "add a key/value to the session"
  [request response k v]
  (assoc response
         :session (assoc (:session request) k v)))

(defn- remove-from-session
  "remove a key from the session"
  [request response k]
  (assoc response
         :session (dissoc (:session request) k)))

(defn- log-in
  "Send the user through the oauth flow"
  [request]
  (remove-from-session
    (add-to-session
      request
      (redirect oauth-login)
      :next (:uri request))
    :next))

(defn- handle-oauth
  "Handle the oauth flow"
  [{params :query-params session :session}]
  (let [code (params "code")
        token ((get-request (str token-uri code)) "access_token")]
    (add-to-session
      (redirect (:next session))
      :token token)))

(defn- with-token
  "bind the oauth token"
  [request handler]
  (binding [*token* (-> request :session :token)]
    (handler request)))

(defn in-bloom
  "Middleware to handle authentication into inBloom
   If the request is already logged in, forward to the handler
   Otherwise, will go through the inBloom OAuth flow"
  [handler {:keys [api-server client-id secret app-server redirect]}]
  (init! api-server client-id secret app-server redirect)
  (wrap-params (wrap-session
    (fn [{session :session path :uri :as request}]
      (if (= redirect-path path) handle-oauth
        (if (logged-in? request) (with-token request handler)
          (log-in request)))))))
