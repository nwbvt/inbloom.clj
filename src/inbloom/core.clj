(ns inbloom.core
  (:require [clj-http.client :as client]
           [clojure.data.json :as json])
  (:use [slingshot.slingshot :only [throw+ try+]]
        [ring.util.response :only [redirect]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.session.cookie :only [cookie-store]]
        [ring.middleware.params :only [wrap-params]]))

(def ^:dynamic *token*)

(defn- init!
  "Initialize everything"
  [api-server client-id secret app-server redirect] (let [api-server (or api-server "https://api.sandbox.inbloom.org")
        app-server (or app-server "localhost:3000")
        redirect (or redirect "/oauth")
        redirect-resource (str app-server redirect)]
    (def api-server api-server)
    (def redirect-path redirect)
    (def api-url (str api-server "/api/rest/v1.1/"))
    (def token-uri (str api-server "/api/oauth/token?client_id=" client-id "&redirect_uri="
                        redirect-resource "&client_secret=" secret "&code="))
    (def oauth-login (str api-server "/api/oauth/authorize?response_type=code&client_id="
                          client-id "&redirect_uri=" redirect-resource))))

(defn- make-uri
  "creates the uri
  basically if the resource already starts with the api url, keep it as it is
  otherwise, append the resource to the api url"
  [resource]
  (if (.startsWith resource api-server) resource (str api-url resource)))

(defn get-request
  "Make a request the api"
  [resource]
  (let [uri (make-uri resource)]
    (json/read-str (:body (client/get uri {:oauth-token *token*})))))

(defn- logged-in?
  "is the user logged in?"
  [{{token :token} :session}]
  (not (nil? token)))

(defn- add-to-session
  "add a key/value to the session"
  [session response k v]
  (assoc response
         :session (assoc session k v)))

(defn- log-in
  "Send the user through the oauth flow"
  [request]
  (let [requested (:uri request)]
    (add-to-session
      (:session request)
      (redirect oauth-login)
      :next requested)))

(defn- handle-oauth
  "Handle the oauth flow"
  [{params :query-params session :session}]
  (let [code (params "code")
        uri (str token-uri code)
        token ((get-request uri) "access_token")
        redirect-to (:next session)
        response (add-to-session session (redirect redirect-to) :token token)]
    response))

(defn- with-token
  "bind the oauth token"
  [request handler]
  (binding [*token* (-> request :session :token)]
    (let [response (handler request)]
      response)))

(defn in-bloom
  "Middleware to handle authentication into inBloom
   If the request is already logged in, forward to the handler
   Otherwise, will go through the inBloom OAuth flow"
  [handler & {:keys [api-server client-id secret app-server redirect]}]
  (init! api-server client-id secret app-server redirect)
  (wrap-params
    (wrap-session
      (fn [{session :session path :uri :as request}]
        (if (= redirect-path path) (handle-oauth request)
          (if (logged-in? request) (with-token request handler)
            (log-in request))))
      {:store (cookie-store)})))
