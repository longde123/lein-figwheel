(ns figwheel.repl
  (:require
   [clojure.string :as string]
   #?@(:cljs [[goog.object :as gobj]
              [goog.storage.mechanism.mechanismfactory :as storage-factory]
              [goog.Uri :as guri]
              [goog.string :as gstring]
              [goog.net.jsloader :as loader]
              [goog.net.XhrIo :as xhrio]
              [goog.log :as glog]
              [goog.array :as garray]
              [goog.json :as gjson]
              [goog.html.legacyconversions :as conv]
              [goog.userAgent.product :as product]]
       :clj [[clojure.data.json :as json]
             [clojure.set :as set]
             [clojure.edn :as edn]
             [clojure.java.browse :as browse]
             [cljs.repl]
             [cljs.stacktrace]
             [clojure.string :as string]
             [figwheel.server.ring]]))
  (:import
   #?@(:cljs [goog.net.WebSocket
              goog.debug.Console
              [goog.Uri QueryData]
              [goog Promise]
              [goog.storage.mechanism HTML5SessionStorage]]
       :clj [java.util.concurrent.ArrayBlockingQueue])))

#?(:cljs (do

;; TODO dev only
(enable-console-print!)

;; --------------------------------------------------
;; Logging
;; --------------------------------------------------
;;
;; Levels
;; goog.debug.Logger.Level.(SEVERE WARNING INFO CONFIG FINE FINER FINEST)
;;
;; set level (.setLevel logger goog.debug.Logger.Level.INFO)
;; disable   (.setCapturing log-console false)

(defonce logger (glog/getLogger "Figwheel REPL"))
(defonce log-console (let [c (goog.debug.Console.)]
                       ;; don't display time
                       (doto (.getFormatter c)
                         (gobj/set "showAbsoluteTime" false)
                         (gobj/set "showRelativeTime" false))
                       c))
(defonce init-logger (do (.setCapturing log-console true) true))
(defn debug [msg]
  (glog/log logger goog.debug.Logger.Level.FINEST msg))

;; TODO dev
(.setLevel logger goog.debug.Logger.Level.FINEST )

;; --------------------------------------------------------------
;; Bootstrap goog require reloading
;; --------------------------------------------------------------

(declare queued-file-reload)

(defn unprovide! [ns]
  (let [path (gobj/get js/goog.dependencies_.nameToPath ns)]
    (gobj/remove js/goog.dependencies_.visited path)
    (gobj/remove js/goog.dependencies_.written path)
    (gobj/remove js/goog.dependencies_.written (str js/goog.basePath path))))

;; this will not work unless bootstrap has been called
(defn figwheel-require [src reload]
  ;; require is going to be called
  (set! (.-require js/goog) figwheel-require)
  (when (= reload "reload-all")
    (set! (.-cljsReloadAll_ js/goog) true))
  (when (or reload (.-cljsReloadAll_ js/goog))
    (unprovide! src))
  (let [res (.require_figwheel_backup_ js/goog src)]
    (when (= reload "reload-all")
      (set! (.-cljsReloadAll_ js/goog) false))
    res))

(defn bootstrap-goog-base
  "Reusable browser REPL bootstrapping. Patches the essential functions
  in goog.base to support re-loading of namespaces after page load."
  []
  ;; The biggest problem here is that clojure.browser.repl might have
  ;; patched this or might patch this afterward
  (when-not js/COMPILED
    (when-not (.-require_figwheel_backup_ js/goog)
      (set! (.-require_figwheel_backup_ js/goog) (or js/goog.require__ js/goog.require)))
    (set! (.-isProvided_ js/goog) (fn [name] false))
    (when-not (and (exists? js/cljs)
                   (exists? js/cljs.user))
      (goog/constructNamespace_ "cljs.user"))
    (set! (.-CLOSURE_IMPORT_SCRIPT goog/global) queued-file-reload)
    (set! (.-require js/goog) figwheel-require)))

(defn patch-goog-base []
  (defonce bootstrapped-cljs (do (bootstrap-goog-base) true)))

;; --------------------------------------------------------------
;; File reloading on different platforms
;; --------------------------------------------------------------

;; this assumes no query string on url
(defn add-cache-buster [url]
  (.makeUnique (guri/parse url)))

(def gloader
  (cond
    (exists? loader/safeLoad)
    #(loader/safeLoad (conv/trustedResourceUrlFromString (str %1)) %2)
    (exists? loader/load) #(loader/load (str %1) %2)
    :else (throw (ex-info "No remote script loading function found." {}))))

(defn reload-file-in-html-env
  [request-url callback]
  {:pre [(string? request-url) (not (nil? callback))]}
  (doto (gloader (add-cache-buster request-url) #js {:cleanupWhenDone true})
    (.addCallback #(apply callback [true]))
    (.addErrback  #(apply callback [false]))))

(def ^:export write-script-tag-import reload-file-in-html-env)

(defn ^:export worker-import-script [request-url callback]
  {:pre [(string? request-url) (not (nil? callback))]}
  (callback (try
              (do (.importScripts js/self (add-cache-buster request-url))
                  true)
              (catch js/Error e
                (glog/error logger (str  "Figwheel: Error loading file " request-url))
                (glog/error logger e)
                false))))

(defn ^:export create-node-script-import-fn []
  (let [node-path-lib (js/require "path")
        ;; just finding a file that is in the cache so we can
        ;; figure out where we are
        util-pattern (str (.-sep node-path-lib)
                          (.join node-path-lib "goog" "bootstrap" "nodejs.js"))
        util-path (gobj/findKey js/require.cache (fn [v k o] (gstring/endsWith k util-pattern)))
        parts     (-> (string/split util-path #"[/\\]") pop pop)
        root-path (string/join (.-sep node-path-lib) parts)]
    (fn [request-url callback]
      (assert (string? request-url) (not (nil? callback)))
      (let [cache-path (.resolve node-path-lib root-path request-url)]
        (gobj/remove (.-cache js/require) cache-path)
        (callback (try
                    (js/require cache-path)
                    (catch js/Error e
                      (glog/error logger (str  "Figwheel: Error loading file " cache-path))
                      (glog/error logger e)
                      false)))))))

(def host-env
  (cond
    (not (nil? goog/nodeGlobalRequire)) :node
    (not (nil? goog/global.document)) :html
    (and (exists? goog/global.navigator)
         (= goog/global.navigator.product "ReactNative"))
    :react-native
    (and
     (nil? goog/global.document)
     (exists? js/self)
     (exists? (.-importScripts js/self)))
    :worker))

(def reload-file*
  (condp = host-env
    :node (create-node-script-import-fn)
    :html write-script-tag-import
    :worker worker-import-script
    (fn [a b] (throw "Reload not defined for this platform"))))

;; TODO Should just leverage the import script here somehow
(defn reload-file [{:keys [request-url] :as file-msg} callback]
  {:pre [(string? request-url) (not (nil? callback))]}
  (glog/fine logger (str "Attempting to load " request-url))
  ((or (gobj/get goog.global "FIGWHEEL_IMPORT_SCRIPT") reload-file*)
   request-url
   (fn [success?]
     (if success?
       (do
         (glog/fine logger (str "Successfully loaded " request-url))
         (apply callback [(assoc file-msg :loaded-file true)]))
       (do
         (glog/error logger (str  "Error loading file " request-url))
         (apply callback [file-msg]))))))

;; for goog.require consumption
(defonce reload-promise-chain (atom (Promise. #(%1 true))))

(defn queued-file-reload
  ([url] (queued-file-reload url nil))
  ([url opt-source-text]
   (when-let [next-promise-fn
              (cond opt-source-text
                #(.then %
                        (fn [_]
                          (Promise.
                           (fn [r _]
                             (try (js/eval opt-source-text)
                                  (catch js/Error e
                                    (glog/error logger e)))
                             (r true)))))
                url
                #(.then %
                        (fn [_]
                          (Promise.
                           (fn [r _]
                             (reload-file {:request-url url}
                                          (fn [file-msg]
                                            (r true))))))))]
     (swap! reload-promise-chain next-promise-fn))))

(defn ^:export after-reloads [f]
  (swap! reload-promise-chain #(.then % (fn [_] (Promise. f)))))

;; --------------------------------------------------------------
;; REPL print forwarding
;; --------------------------------------------------------------

(goog-define print-output "console,repl")

(defn print-receivers [outputs]
  (->> (string/split outputs #",")
       (map string/trim)
       (filter (complement string/blank?))
       (map keyword)
       distinct))

(defmulti out-print (fn [k args] k))
(defmethod out-print :console [_ args]
  (.apply (.-log js/console) js/console (garray/clone (to-array args))))

(defmulti err-print (fn [k args] k))
(defmethod err-print :console [_ args]
  (.apply (.-error js/console) js/console (garray/clone (to-array args))))

(defn setup-printing! []
  (let [printers (print-receivers print-output)]
    (set-print-fn! (fn [& args] (doseq [p printers] (out-print p args))))
    (set-print-err-fn! (fn [& args] (doseq [p printers] (err-print p args))))))

#_ (printing-receivers "console,repl")

;; --------------------------------------------------------------
;; Websocket REPL
;; --------------------------------------------------------------

(goog-define connect-url "ws://localhost:3449/figwheel-connect")

(def state (atom {}))

;; returns nil if not available
(def storage (storage-factory/createHTML5SessionStorage "figwheel.repl"))

(defn set-state [k v]
  (swap! state assoc k v)
  (when storage (.set storage (str k) v)))

(defn get-state [k]
  (if storage (.get storage (str k)) (get @state k)))

(defn ^:export session-name [] (get-state ::session-name))
(defn ^:export session-id [] (get-state ::session-id))

(defn response-for [{:keys [uuid]} response-body]
  (cond->
      {:session-id   (session-id)
       :session-name (session-name)
       :response response-body}
    uuid (assoc :uuid uuid)))

(defn respond-to [{:keys [websocket http-url] :as old-msg} response-body]
  (let [response (response-for old-msg response-body)]
    (cond
      websocket
      (.send websocket (pr-str response))
      http-url
      (xhrio/send http-url
                  (fn [e] (debug "Response Posted"))
                  "POST"
                  (pr-str response)))))

(defmulti message :op)
(defmethod message "naming" [msg]
  (when-let [sn  (:session-name msg)] (set-state ::session-name sn))
  (when-let [sid (:session-id msg)]   (set-state ::session-id sid))
  (glog/info logger (str "Session ID: "   (session-id)))
  (glog/info logger (str "Session Name: " (session-name))))

(defmethod message "ping" [msg] (respond-to msg {:pong true}))

(def ^:dynamic *eval-js* js/eval)

(let [ua-product-fn
      ;; TODO make sure this works on other platforms
      #(cond
         (not (nil? goog/nodeGlobalRequire)) :chrome
         product/SAFARI    :safari
         product/CHROME    :chrome
         product/FIREFOX   :firefox
         product/IE        :ie)]
  (defn eval-javascript** [code]
    (let [ua-product (ua-product-fn)]
      (try
        (let [sb (js/goog.string.StringBuffer.)]
          ;; TODO capture err as well?
          (binding [cljs.core/*print-newline* false
                    cljs.core/*print-fn* (fn [x] (.append sb x))]
            (let [result-value (*eval-js* code)]
              {:status :success
               :out (str sb)
               :ua-product ua-product
               :value result-value})))
        (catch js/Error e
          {:status :exception
           :value (pr-str e)
           :ua-product ua-product
           :stacktrace (.-stack e)})
        (catch :default e
          {:status :exception
           :ua-product ua-product
           :value (pr-str e)
           :stacktrace "No stacktrace available."})))))

(defmethod message "eval" [{:keys [code] :as msg}]
  (let [result (eval-javascript** code)]
    (respond-to msg result)))

(defmethod message "messages" [{:keys [messages http-url]}]
  (doseq [msg messages]
    (message (cond-> (js->clj msg :keywordize-keys true)
               http-url (assoc :http-url http-url)))))

(defn fill-url-template [connect-url']
  (if (= host-env :html)
      (-> connect-url'
          (string/replace "[[client-hostname]]" js/location.hostname)
          (string/replace "[[client-port]]" js/location.port))
      connect-url'))

(defn make-url [connect-url']
  (let [uri (guri/parse (fill-url-template (or connect-url' connect-url)))]
    (cond-> (.add (.getQueryData uri) "fwsid" (or (session-id) (random-uuid)))
      (session-name) (.add "fwsname" (session-name)))
    uri))

(defn exponential-backoff [attempt]
  (* 1000 (min (js/Math.pow 2 attempt) 20)))

(defn hook-repl-printing-output! [respond-msg]
  (defmethod out-print :repl [_ args]
    (glog/info logger (str "Printing" (pr-str args)))
    (respond-to respond-msg
                {:output true
                 :stream :out
                 :args (mapv #(if (string? %) % (gjson/serialize %)) args)}))
  (defmethod err-print :repl [_ args]
    (respond-to respond-msg
                {:output true
                 :stream :err
                 :args (mapv #(if (string? %) % (gjson/serialize %)) args)}))
  (setup-printing!))

(defn ws-connect [& [websocket-url']]
  (let [websocket (goog.net.WebSocket.)
        url (str (make-url websocket-url'))]
    (patch-goog-base)
    (doto websocket
      (.addEventListener goog.net.WebSocket.EventType.MESSAGE
                         (fn [e]
                           (when-let [msg (gobj/get e "message")]
                             (try
                               (debug msg)
                               (message (assoc
                                         (js->clj (js/JSON.parse msg) :keywordize-keys true)
                                         :websocket websocket))
                               (catch js/Error e
                                 (glog/error logger e))))))
      (.addEventListener goog.net.WebSocket.EventType.OPENED
                         (fn [e]
                           (hook-repl-printing-output! {:websocket websocket})
                           (js/console.log "OPENED")
                           (js/console.log e)))
      (.open url))))

;; -----------------------------------------------------------
;; HTTP simple and long polling
;; -----------------------------------------------------------

(defn http-get [url]
  (Promise.
   (fn [succ err]
     (xhrio/send
      url
      (fn [e]
        (let [xhr (gobj/get e "target")]
          (if (.isSuccess xhr)
            (succ (.getResponseJson xhr))
            (err xhr))))))))

(declare http-connect http-connect*)

(defn poll [msg-fn connect-url']
  (.then (http-get (make-url connect-url'))
         (fn [msg]
           (msg-fn msg)
           (js/setTimeout #(poll msg-fn connect-url') 500))
         (fn [e] ;; lost connection
           (http-connect connect-url'))))

(defn long-poll [msg-fn connect-url']
  (.then (http-get (make-url connect-url'))
         (fn [msg]
           (msg-fn msg)
           (long-poll msg-fn connect-url'))
         (fn [e] ;; lost connection
           (http-connect connect-url'))))

(defn http-connect* [attempt connect-url']
  (let [url (make-url connect-url')
        surl (str url)
        msg-fn (fn [msg]
                 (try
                   (debug (pr-str msg))
                   (message (assoc (js->clj msg :keywordize-keys true)
                                   :http-url surl))
                   (catch js/Error e
                     (glog/error logger e))))]
    (doto (.getQueryData url)
      (.add "fwinit" "true"))
    (.then (http-get url)
           (fn [msg]
             (let [typ (gobj/get msg "connection-type")]
               (glog/info logger (str "Connected: " typ))
               (msg-fn msg)
               ;; after connecting setup printing redirects
               (hook-repl-printing-output! {:http-url surl})
               (if (= typ "http-long-polling")
                 (long-poll msg-fn connect-url')
                 (poll msg-fn connect-url'))))
           (fn [e];; didn't connect
             (when (instance? js/Error e)
               (glog/error logger e))
             (when (and (instance? goog.net.XhrIo e) (.getResponseBody e))
               (debug (.getResponseBody e)))
             (let [wait-time (exponential-backoff attempt)]
               (glog/info logger (str "HTTP Connection Error: next connection attempt in " (/ wait-time 1000) " seconds"))
               (js/setTimeout #(http-connect* (inc attempt) connect-url')
                              wait-time))))))

(defn http-connect [& [connect-url']]
  (http-connect* 0 connect-url'))

(defn connect [& [connect-url']]
  (let [url (string/trim (or connect-url' connect-url))]
    (cond
      (gstring/startsWith url "ws")   (ws-connect url)
      (gstring/startsWith url "http") (http-connect url))))

))


#?(:clj (do

(defonce ^:private listener-set (atom #{}))
(defn add-listener [f] (swap! listener-set conj f) nil)
(defn remove-listener [f] (swap! listener-set disj f) nil)

(declare name-list)

(defn log [& args]
  (spit "server.log" (apply prn-str args) :append true))

(defonce scratch (atom {}))

(def ^:dynamic *server* nil)

(defn parse-query-string [qs]
  (when (string? qs)
    (into {} (for [[_ k v] (re-seq #"([^&=]+)=([^&]+)" qs)]
               [(keyword k) v]))))

;; ------------------------------------------------------------------
;; Connection management
;; ------------------------------------------------------------------

(defonce ^:dynamic *connections* (atom {}))

(defn taken-names [connections]
  (set (mapv :session-name (vals connections))))

(defn available-names [connections]
  (set/difference name-list (taken-names connections)))

(defn negotiate-id [ring-request connections]
  (let [query (parse-query-string (:query-string ring-request))
        sid (:fwsid query (str (java.util.UUID/randomUUID)))
        sname (or (some-> connections (get sid) :session-name)
                  (when-let [chosen-name (:fwsname query)]
                    (when-not ((taken-names connections) chosen-name)
                      chosen-name))
                  (rand-nth (seq (available-names connections))))]
    [sid sname]))

(defn create-connection! [ring-request options]
  (let [[sess-id sess-name] (negotiate-id ring-request @*connections*)
        conn (merge (select-keys ring-request [:server-port :scheme :uri :server-name :query-string :request-method])
                    {:session-name sess-name
                     :session-id sess-id
                     ::alive-at (System/currentTimeMillis)
                     :created-at (System/currentTimeMillis)}
                    options)]
    (swap! *connections* assoc sess-id conn)
    conn))

(defn remove-connection! [{:keys [session-id] :as conn}]
  (swap! *connections* dissoc session-id))

(defn receive-message! [data]
  (when-let [data
             (try (edn/read-string data)
                  (catch Throwable t (binding [*out* *err*] (clojure.pprint/pprint (Throwable->map t)))))]
    (doseq [f @listener-set]
      (try (f data) (catch Throwable ex)))))

(defn naming-response [{:keys [session-name session-id type] :as conn}]
  (json/write-str {:op :naming
                   :session-name session-name
                   :session-id session-id
                   :connection-type type}))

;; ------------------------------------------------------------------
;; Websocket behavior
;; ------------------------------------------------------------------

(defn abstract-websocket-connection [connections]
  (let [conn (volatile! nil)]
    {:on-connect (fn [{:keys [request send-fn close-fn is-open-fn]
                       :as connect-data}]
                   ;; TODO remove dev only
                   (swap! scratch assoc :ring-request request)
                   (binding [*connections* connections]
                     (let [conn' (create-connection!
                                  request
                                  {:type :websocket
                                   :is-open-fn is-open-fn
                                   :close-fn close-fn
                                   :send-fn (fn [_ data]
                                              (send-fn data))})]
                       (vreset! conn conn')
                       (send-fn (naming-response conn')))))
     :on-close   (fn [status] (binding [*connections* connections]
                                (remove-connection! @conn)))
     :on-receive (fn [data] (binding [*connections* connections]
                              (receive-message! data)))}))

;; ------------------------------------------------------------------
;; http polling
;; ------------------------------------------------------------------

(defn json-response [json-body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body json-body})

(defn http-polling-send [conn data]
  (swap! (::comm-atom conn) update :messages (fnil conj []) data))

(defn http-polling-connect [ring-request]
  (let [{:keys [fwsid fwinit]} (-> ring-request :query-string parse-query-string)]
    ;; new connection create the connection
    (cond
      (not (get @*connections* fwsid))
      (let [conn (create-connection! ring-request
                                     {:type :http-polling
                                      ::comm-atom (atom {})
                                      :is-open-fn (fn [conn]
                                                    (< (- (System/currentTimeMillis)
                                                          (::alive-at conn))
                                                       3000))
                                      :send-fn http-polling-send})]
        (json-response (naming-response conn)))
      fwinit
      (let [conn (get @*connections* fwsid)]
        (swap! *connections* assoc-in [fwsid :created-at] (System/currentTimeMillis))
        (json-response (naming-response conn)))
      :else
      ;; otherwise we are polling
      (let [messages (volatile! [])
            comm-atom (get-in @*connections* [fwsid ::comm-atom])]
        (swap! *connections* assoc-in [fwsid ::alive-at] (System/currentTimeMillis))
        (swap! comm-atom update :messages (fn [msgs] (vreset! messages (or msgs [])) []))
        (json-response
         (json/write-str {:op :messages
                          :messages (mapv json/read-json @messages)
                          :connection-type :http-polling}))))))

(defn http-polling-endpoint [ring-request]
  (condp = (:request-method ring-request)
    :get (http-polling-connect ring-request)
    :post (do (receive-message! (slurp (:body ring-request)))
              {:status 200
               :headers {"Content-Type" "text/html"}
               :body "Received"})))

;; simple http polling can be included in any ring middleware stack
(defn http-polling-middleware [handler path connections]
  (fn [ring-request]
    (if-not (.startsWith (:uri ring-request) path)
      (handler ring-request)
      (binding [*connections* connections]
        (http-polling-endpoint ring-request)))))

;; ------------------------------------------------------------------
;; http async polling - long polling
;; ------------------------------------------------------------------
;; long polling is a bit complex - but this currently appears to work as well
;; as websockets, it is heavier overall and much harder to determine when it is closed

(declare send-for-response)

(defn ping [conn] (send-for-response [conn] {:op :ping}))

;; could make no-response behavior configurable
(defn ping-thread [connections fwsid {:keys [interval
                                             ping-timeout]
                                      :or {interval 15000
                                           ping-timeout 2000}}]
  (doto (Thread.
         (fn []
           (loop []
             (Thread/sleep interval)
             (when-let [conn (get @connections fwsid)]
               (if-not (try
                         ;; TODO consider re-trying a couple times on failure
                         (deref (ping conn) ping-timeout false)
                         (catch Throwable e
                           false))
                 (swap! connections dissoc fwsid)
                 (recur))))))
    (.setDaemon true)
    (.start)))

;; agents would be easier but heavier and agent clean up is harder
(defn long-poll-send [comm-atom msg]
  (let [data (volatile! nil)
        add-message #(if-not msg % (update % :messages (fnil conj []) msg))]
    (swap! comm-atom
           (fn [{:keys [respond messages] :as comm}]
             (if (and respond (or (not-empty messages) msg))
               (do (vreset! data (add-message comm)) {})
               (add-message comm))))
    (when-let [{:keys [respond messages]} @data]
      ;; when this fails?
      (respond
       (json-response
        (json/write-str {:op :messages
                         :messages (mapv json/read-json messages)
                         :connection-type :http-long-polling}))))))

(defn long-poll-capture-respond [comm-atom respond]
  (let [has-messages (volatile! false)]
    (swap! comm-atom (fn [{:keys [messages] :as comm}]
                       (vreset! has-messages (not (empty? messages)))
                       (assoc comm :respond respond)))
    (when @has-messages (long-poll-send comm-atom nil))))

;; may turn this into a multi method
(defn connection-send [{:keys [send-fn] :as conn} data]
  (send-fn conn data))

(defn send-for-response [connections msg]
  ;; TODO remove all listeners after single response?
  ;; or have one listener and one uuid
  (let [prom (promise)]
    (doseq [conn connections]
      (let [uuid (str (java.util.UUID/randomUUID))
            listener (fn listen [msg]
                       (when (= uuid (:uuid msg))
                         (when-let [result (:response msg)]
                           (deliver prom
                                    (if (instance? clojure.lang.IMeta result)
                                      (vary-meta result assoc ::message msg)
                                      result)))
                         (remove-listener listen)))]
        (add-listener listener)
        (connection-send
         conn
         (json/write-str
          (-> (select-keys conn [:session-id :session-name])
              (merge msg)
              (assoc :uuid uuid))))))
    prom))

(defn http-long-polling-connect [ring-request respond raise]
  (let [{:keys [fwsid fwinit]} (-> ring-request :query-string parse-query-string)]
    (if (not (get @*connections* fwsid))
      (let [conn (create-connection!
                  ring-request
                  {:type :http-long-polling
                   ::comm-atom (atom {:messages []})
                   :is-open-fn (fn [conn]
                                 (not (> (- (System/currentTimeMillis)
                                            (::alive-at conn))
                                         20000)))
                   :send-fn (fn [conn msg]
                              (long-poll-send (::comm-atom conn) msg))})]
        (respond (json-response (naming-response conn)))

        ;; keep alive with ping thread
        ;; This behavior is much more subtle that it appears, it is far better
        ;; than webserver triggered async timeout because it doesn't
        ;; leave behind an orphaned respond-fn
        ;; also it helps us remove lost connections, as I haven't found
        ;; a way to discover if an HTTPChannel is closed on the remote endpoint

        ;; TODO a short ping-timeout could be a problem if an
        ;; env has a long running eval
        ;; this could reuse the eval timeout
        (ping-thread *connections* fwsid {:interval 15000 :ping-timeout 2000}))
      (let [conn (get @*connections* fwsid)]
        (if fwinit
          (do
            (respond (json-response (naming-response conn)))
            (swap! *connections* assoc-in [fwsid :created-at] (System/currentTimeMillis)))
          (do
            (long-poll-capture-respond (::comm-atom conn) respond)
            (swap! *connections* assoc-in [fwsid ::alive-at] (System/currentTimeMillis))))))))

(defn http-long-polling-endpoint [ring-request send raise]
  (condp = (:request-method ring-request)
    :get (http-long-polling-connect ring-request send raise)
    :post (do (receive-message! (slurp (:body ring-request)))
              (send {:status 200
                     :headers {"Content-Type" "text/html"}
                     :body "Received"}))))

(defn asyc-http-polling-middleware [handler path connections]
  (fn [ring-request send raise]
    (swap! scratch assoc :async-request ring-request)
    (if-not (.startsWith (:uri ring-request) path)
      (handler ring-request send raise)
      (binding [*connections* connections]
        (try
          (http-long-polling-endpoint ring-request send raise)
          (catch Throwable e
            (raise e)))))))

;; ---------------------------------------------------
;; ReplEnv implmentation
;; ---------------------------------------------------

(defn open-connections []
  (filter (fn [{:keys [is-open-fn] :as conn}]
            (or (nil? is-open-fn) (is-open-fn conn)))
          (vals @*connections*)))

(defn connections-available [repl-env]
  (sort-by
   :created-at >
   (filter (or (some-> repl-env :connection-filter deref)
               identity)
           (open-connections))))

(defn wait-for-connection [repl-env]
  (loop []
    (when (empty? (connections-available repl-env))
      (Thread/sleep 500)
      (recur))))

(defn send-for-eval [connections js]
  (send-for-response connections {:op :eval :code js}))

(defn eval-connections [{:keys [focus-session-name
                                broadcast] :as repl-env}]
  (let [connections (connections-available repl-env)
          ;; session focus
        connections (if-let [focus-conn
                             (and @focus-session-name
                                  (first (filter (fn [{:keys [session-name]}]
                                                   (= @focus-session-name
                                                      session-name))
                                                 connections)))]
                      [focus-conn]
                      (do
                        (reset! focus-session-name nil)
                        connections))]
    (if broadcast connections (take 1 connections))))

(defn trim-last-newline [args]
  (if-let [args (not-empty (filter string? args))]
    (conj (vec (butlast args))
          (string/trim-newline (last args)))
    args))

(defn print-to-stream [stream args]
  (condp = stream
    :out (apply println args)
    :err (binding [*out* *err*]
           (apply println args))))

(defn repl-env-print [repl-env stream args]
  (when-let [args (not-empty (filter string? args))]
    (when (and (:out-print-fn repl-env) (= :out stream))
      (apply (:out-print-fn repl-env) args))
    (when (and (:err-print-fn repl-env) (= :err stream))
      (apply (:err-print-fn repl-env) args))
    (let [args (trim-last-newline args)]
      (when (:print-to-output-streams repl-env)
        (if-let [bprinter @(:bound-printer repl-env)]
          (bprinter stream args)
          (print-to-stream stream args))))))

(let [timeout-val (Object.)]
  (defn evaluate [{:keys [focus-session-name ;; just here for consideration
                          repl-timeout
                          broadcast] :as repl-env} js]
    (reset! (:bound-printer repl-env)
            (bound-fn [stream args]
              (print-to-stream stream args)))
    (wait-for-connection repl-env)
    (let [ev-connections (eval-connections repl-env)
          result (let [v (deref (send-for-eval ev-connections js)
                                (or repl-timeout 8000)
                                timeout-val)]
                   (if (= timeout-val v)
                     {:status :exception
                      :value "Eval timed out!"
                      :stacktrace "No stacktrace available."}
                     v))]
      (when-let [out (:out result)]
        (when (not (string/blank? out))
          (repl-env-print repl-env :out [(string/trim-newline out)])))
      result)))

(defn require-resolve [symbol-str]
  (let [sym (symbol symbol-str)]
    (when-let [ns (namespace sym)]
      (try
        (require (symbol ns))
        (resolve sym)
        (catch Throwable e
          nil)))))

#_(require-resolve 'figwheel.server.jetty-websocket/run-server)

;; TODO more precise error when loaded but fn doesn't exist
(defn dynload [ns-sym-str]
  (let [resolved (require-resolve ns-sym-str)]
    (if resolved
      resolved
      (throw (ex-info (str "Figwheel: Unable to dynamicly load " ns-sym-str)
                      {:not-loaded ns-sym-str})))))

;; taken from ring server
(defn try-port
  "Try running a server under one port or a list of ports. If a list of ports
  is supplied, try each port until it succeeds or runs out of ports."
  [port server-fn]
  (if-not (sequential? port)
    (server-fn port)
    (try (server-fn (first port))
         (catch java.net.BindException ex
           (if-let [port (next port)]
             (try-port port server-fn)
             (throw ex))))))

(defn run-default-server*
  [options connections]
  ;; require and run figwheel server
  (let [server-fn (dynload (get options :ring-server
                                'figwheel.server.jetty-websocket/run-server))
        figwheel-connect-path (get options :figwheel-connect-path "/figwheel-connect")]
    (server-fn
     ((dynload (get options :ring-stack 'figwheel.server.ring/default-stack))
      (:ring-handler options)
      (:ring-stack-options options))
     (assoc (get options :ring-server-options)
            :async-handlers
            {figwheel-connect-path
             (-> (fn [ring-request send raise]
                   (send {:status 404
                          :headers {"Content-Type" "text/html"}
                          :body "Not found: figwheel http-async-polling"}))
                 (asyc-http-polling-middleware figwheel-connect-path connections)
                 (figwheel.server.ring/wrap-async-cors
                  :access-control-allow-origin #".*"
                  :access-control-allow-methods
                  [:head :options :get :put :post :delete :patch]))}
            ::abstract-websocket-connections
            {figwheel-connect-path
             (abstract-websocket-connection connections)}))))

(defn run-default-server [options connections]
  (run-default-server* (update options :ring-server-options
                               #(merge (select-keys options [:host :port]) %))
                       connections))

(defn setup [repl-env opts]
  (when (and
         (or (not (bound? #'*server*))
             (nil? *server*))
         (nil? @(:server-kill repl-env)))
    (let [server (run-default-server
                  ;; TODO merge in options here for ring-handler
                  {:port 9500}
                  *connections*)]
      (reset! (:server-kill repl-env) (fn [] (.stop server)))))
  ;; printing
  (let [print-listener
        (bound-fn [{:keys [session-id session-name uuid response] :as msg}]
          (when (and session-id (not uuid) (get response :output))
            (let [session-ids (set (map :session-id (eval-connections repl-env)))]
              (when (session-ids session-id)
                (let [{:keys [stream args]} response]
                  (when (and stream (not-empty args))
                    ;; when printing a result from several sessions mark it
                    (let [args (if-not (= 1 (count session-ids))
                                 (cons (str "[Session:-----:" session-name "]\n") args)
                                 args)]
                      (repl-env-print repl-env stream args))))))))]
    (reset! (:printing-listener repl-env) print-listener)
    (add-listener print-listener))
  ;; not sure about this yet
    #_(doseq [url (:open-urls this)]
        (try (browse/browse-url url)
             (catch Throwable e
               (->> (str (when-let [m (.getMessage e)] (str ": " m)))
                    (format "Failed to open url %s %s" url)
                    println)))))

(defrecord FigwheelReplEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    (setup this opts)
    #_(wait-for-connection this))
  (-evaluate [this _ _  js]
    ;; print where eval occurs
    (evaluate this js))
  (-load [this provides url]
    ;; load a file into all the appropriate envs
    (when-let [js-content (try (slurp url) (catch Throwable t))]
      (evaluate this js-content)))
  (-tear-down [{:keys [server-kill printing-listener]}]
    (when-let [kill-fn @server-kill]
      (reset! server-kill nil)
      (kill-fn))
    (when-let [listener @printing-listener]
      (remove-listener listener)))
  cljs.repl/IReplEnvOptions
  (-repl-options [this])
  cljs.repl/IParseStacktrace
  (-parse-stacktrace [this st err opts]
    (cljs.stacktrace/parse-stacktrace this st err opts)))

(defn repl-env* [{:keys [host port worker-name-prefix connection-filter ring-handler]
                  :or {connection-filter identity
                       port 9500
                       host "localhost"} :as opts}]
  (merge (FigwheelReplEnv.)
         ;; TODO move to one atom
         {:server-kill (atom nil)
          :printing-listener (atom nil)
          :bound-printer (atom nil)
          ;; helpful for nrepl so you can easily
          ;; translate output into messages
          :out-print-fn nil
          :err-print-fn nil
          :print-to-output-streams true
          :open-urls nil
          :connection-filter (atom connection-filter)
          ;; :last-eval-session-ids (atom nil)
          :focus-session-name (atom nil)
          :broadcast false
          :ring-handler nil
          :ring-stack nil
          :port port
          :host host}
         opts))

;; ------------------------------------------------------
;; Connection management
;; ------------------------------------------------------
;;  mostly for use from the REPL

(defn list-connections []
  (let [conns (connections-available cljs.repl/*repl-env*)
        longest-name (apply max (cons (count "Session Name")
                                      (map (comp count :session-name) conns)))]
    (println (format (str "%-" longest-name "s %7s %s")
                     "Session Name"
                     "Age"
                     "URL"))
    (doseq [{:keys [session-name uri query-string created-at]} conns]
      (println (format (str "%-" longest-name "s %6sm %s")
                       session-name
                       (Math/round (/ (- (System/currentTimeMillis) created-at) 60000.0))
                       uri)))))

(defn will-eval-on []
  (if-let [n @(:focus-session-name cljs.repl/*repl-env*)]
    (println "Focused On: " n)
    (println "Will Eval On: " (->> (connections-available cljs.repl/*repl-env*)
                                  first
                                  :session-name))))

(defn conns* []
  (will-eval-on)
  (list-connections))

(defmacro conns []
  (conns*))

(defn focus* [session-name]
  (let [names (map :session-name (connections-available cljs.repl/*repl-env*))
        session-name (name session-name)]
    (if ((set names) session-name)
      (str "Focused On: " (reset! (:focus-session-name cljs.repl/*repl-env*) session-name))
      (str "Error: " session-name " not in " (pr-str names)))))

(defmacro focus [session-name]
  (focus* session-name))

;; TODOS
;; - learn more about https
;; - make work on node and other platforms
;; - make complete dev webserver stack


;; TODO NPE that occurs in open-connections when websocket isn't cleared
;; happens on eval
(comment

  (def serve (run-default-server {:ring-handler
                                  (fn [r]
                                    (throw (ex-info "Testing" {}))
                                    #_{:status 404
                                       :headers {"Content-Type" "text/html"}
                                       :body "Yeppers now"})
                                  :port 9500}
                                 *connections*))

  (.stop serve)

  scratch

  (def re (repl-env* {}))
  (cljs.repl/-setup re {})
  (cljs.repl/-tear-down re)

  (connections-available re)
  (open-connections)
  (evaluate re "33")

  (evaluate re "setTimeout(function() {cljs.core.prn(\"hey hey\")}, 1000);")

  (= (mapv #(:value (evaluate re (str %)))
           (range 100))
     (range 100))

(def x (ping (first (vals @*connections*))))

  (negotiate-id (:ring-request @scratch) @*connections*)

  (def channel (:body (:async-request @scratch)))

  (.isReady channel)

  (ping ( (vals @*connections*))

        )
  (swap! *connections* dissoc "99785176-1793-4814-938a-93bf071acd2f")

  (swap! scratch dissoc :print-msg)
  scratch
  *connections*
  (deref


   )
  (swap! *connections* dissoc "d9ffc9ac-b2ec-4660-93c1-812afd1cb032")
  (parse-query-string (:query-string (:ring-request @scratch)))
  (negotiate-name (:ring-request @scratch) @*connections*)
  (reset! *connections* (atom {}))

  (binding [cljs.repl/*repl-env* re]
    (conns*)
    #_(focus* 'Judson))

  )

(def name-list
  (set (map str '[Sal Julietta Dodie Janina Krista Freeman Angila Cathy Brant Porter Marty Jerrell Stephan Glenn Palmer Carmelina Monroe Eufemia Ciara Thu Stevie Dee Shamika Jazmin Doyle Roselle Lucien Laveta Marshall Rosy Hilde Yoshiko Nicola Elmo Tana Odelia Gigi Mac Tanner Johnson Roselia Gilberto Marcos Shelia Kittie Bruno Leeanne Elicia Miyoko Lilliana Tatiana Steven Vashti Rolando Korey Selene Emilio Fred Marvin Eduardo Jolie Lorine Epifania Jeramy Eloy Melodee Lilian Kim Cory Daniel Grayce Darin Russ Vanita Yan Quyen Kenda Iris Mable Hong Francisco Abdul Judson Boyce Bridget Cecil Dirk Janetta Kelle Shawn Rema Rosie Nakesha Dominick Jerald Shawnda Enrique Jose Vince])))

#_(defonce ^:private message-loop
  (doto (Thread.
         #(let [x (.take messageq)
                listeners @listener-set]
            (doseq [f listeners]
              (try
                (f x)
                (catch Throwable ex)))
            (recur))
         (str ::message-loop))
    (.setDaemon true)
    (.start)))




          ))
