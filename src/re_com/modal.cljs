(ns re-com.modal
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [re-com.core :refer [handler-fn]])
  (:require [cljs.core.async :as    async :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
            [re-com.validate :refer [extract-arg-data validate-args string-or-hiccup?]]
            [re-com.util     :as    util]
            [re-com.core     :refer [spinner progress-bar]]
            [re-com.buttons  :refer [button]]
            [reagent.core    :as    reagent]
            [goog.events     :as    events]))


;; MODAL COMPONENT
;;
;; Use cases
;;  A. I/O work in background
;;      - EASIEST, single I/O action with callback(s).
;;      - User feedback and interaction (e.g. Cancel buttons) is easy
;;      - Use cases:
;;         1. Loading URL (succeeds)
;;             - Could fire off multiple (asynchronous) requests.
;;             - Task is :finished once the final request is completed.
;;         2. Writing to disk (fails!)
;;             - Only a single "thread" of activity.
;;             - Synchronous or asynchronous?
;;
;;  B. Long running/CPU intensive
;;      - User feedback and interaction is a CHALLENGE. We are saturating the JS Event loop.
;;
;;      - SINGLE chunk
;;        - Just need GUI to be locked while we do something.
;;        - Ideally get work done in user acceptable time-frame.
;;        - No need for user interaction (not possible anyway). i.e. NO spinner/progress/cancel
;;        - Use cases:
;;           3. Calculating pivot totals
;;
;;      - MULTI chunks
;;        - MOST complex case
;;        - Too much time taken for a single chunk.
;;        - We chunk the work so we hand back control to the event loop to update UI
;;          and process mouse/keyboard events.
;;        - NEED user interaction. i.e. one or more of spinner/progress/cancel.
;;        - Two types of MULTI:
;;           a. Incrementally processing a single job:
;;               - Break process into multiple fn calls (chunks) and schedule them one after the
;;                 other with a short gap inbetween.
;;               - Pass current progress state to each successive call.
;;               - Use cases:
;;                  4. Processing a large in-memory XML file (chunked).
;;           b. Calling a sequence of jobs which do different processes.
;;               - Associated with each job would be status text which would display on modal.
;;               - Each individual job could potentially be chunked.
;;               - Cancel button would only work between jobs unless a job was chunked.
;;               - Spinner/progress performance would be poor unless each job chunked.
;;               - Use cases:
;;                  5. MWI Enhancer modifying EDN in steps (multiple fn calls, not chunked).
;;                      -
;;                  6. Creating large JSON data for writing (chunked), then writing (a type A I/O job).
;;                      -
;;
;;  C. Nothing happening in background
;;      - User interaction required, isolated from main screen.
;;      - Use cases:
;;         7. Arbitrarily complex input form
;;
;;  D. Errors
;;      - What to do when errors occur during modal processes.
;;      - Use cases:
;;         8. Display an alert box right inside the modal.
;;         9. Pass the error back to the caller.
;;
;; TODO:
;;  - Why doesn't GIF animate?
;;  - Would BS animated progress bar work?
;;  - Alternatives?
;;  - Gobble up backdrop clicks so they don't go to the main window
;;  - Possibly get rid of dependency on alert (unless we will ALWAYS have all components included)


;; ------------------------------------------------------------------------------------
;;  cancel-button
;; ------------------------------------------------------------------------------------

(defn- cancel-button ;; TODO: Only currently used in modal
  "Render a cancel button"
  [callback]
  [:div {:style {:display "flex"}}
   [button
    :label    "Cancel"
    :on-click callback
    :style    {:margin "auto"}
    :class    "btn-info"]])


;; ------------------------------------------------------------------------------------
;;  modal-window
;; ------------------------------------------------------------------------------------

(def modal-window-args-desc
  [{:name :child            :required true                   :type "string | hiccup" :validate-fn string-or-hiccup? :description "Hiccup to be centered within in the browser window"}
   {:name :with-panel       :required false :default true    :type "boolean"                                        :description "true will surround your :child hiccup with a white, rounded panel with some padding"}
   {:name :backdrop-color   :required false :default "black" :type "string"          :validate-fn string?           :description "CSS colour of backdrop"}
   {:name :backdrop-opacity :required false :default 0.85    :type "double | string"                                :description [:span "Opacity of backdrop from:" [:br] "0 (transparent) to 1 (opaque)"]}
   {:name :class            :required false                  :type "string"          :validate-fn string?           :description "CSS class names, space separated"}
   {:name :style            :required false                  :type "map"             :validate-fn map?              :description "CSS styles to add or override"}
   {:name :attr             :required false                  :type "map"             :validate-fn map?              :description [:span "html attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed"]}])

(def modal-window-args (extract-arg-data modal-window-args-desc))

(defn modal-window
  "Renders a modal window centered on screen. A dark transparent backdrop sits between this and the underlying
   main window to prevent UI interactivity and place user focus on the modal window.
   Parameters:
    - child:  The message to display in the modal (a string or a hiccup vector or function returning a hiccup vector)"
  [& {:keys [child with-panel backdrop-color backdrop-opacity class style attr]
      :or   {with-panel true backdrop-color "black" backdrop-opacity 0.85}
      :as   args}]
  {:pre [(validate-args modal-window-args args "modal-window")]}
  (fn []
    [:div (merge {:class  (str "rc-modal-window " class)    ;; Containing div
                  :style (merge {:display  "flex"
                                 :position "fixed"
                                 :left     "0px"
                                 :top      "0px"
                                 :width    "100%"
                                 :height   "100%"}
                                style)}
                 attr)
     [:div {:style {:position "fixed"                       ;; Backdrop
                    :width    "100%"
                    :height   "100%"
                    :background-color backdrop-color
                    :opacity          backdrop-opacity
                    :z-index 1020}}]
     [:div {:style (merge {:margin  "auto"                  ;; Child
                           :z-index 1020}
                          (when with-panel {:background-color "white"
                                            :padding          "16px"
                                            :border-radius    "6px"}))}
      child]]))


;; ------------------------------------------------------------------------------------
;;  looper
;; ------------------------------------------------------------------------------------

(defn looper
  "Parameters:
    - func           A function to repeatedly call. On each call, something else happens, could be the
                     same funciton, could be a different function.
    - initial-state  The initial state to be passed to the first function call.
                     After that, each successive function call is responsible for returning the parameters
                     to be used for the subsequent function call and so on.
    - running?       A reagent boolean atom indicating if the processing is running"
  [& {:keys [initial-value func when-done]}]
  (go (loop [pause (<! (timeout 20))
             val   initial-value]
        (let [[continue? out]  (func val)]
          (if continue?
            (recur (<! (timeout 20)) out)
            (when-done out))))))



;; ------------------------------------------------------------------------------------
;;  domino-process
;; ------------------------------------------------------------------------------------

(defn domino-step
  [continue-fn? in-chan func]
  (let [out-chan (chan)]
    (go (let [in    (<! in-chan)
              pause (<! (timeout 20))
              out   (if (continue-fn?) (func in) in)]
          (>! out-chan (if (nil? out) in out))))
    out-chan))

(defn domino-process
  ([initial-value funcs]
   (domino-process initial-value (atom true) funcs))
  ([initial-value continue? funcs]
  (assert ((complement nil?) initial-value) "Initial value can't be nil because that causes channel problems")
  (let [continue-fn? (fn [] @continue?)
        in-chan   (chan)
        out-chan  (clojure.core/reduce (partial domino-step continue-fn?) in-chan funcs)]
    (put! in-chan initial-value)
    out-chan)))
