;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns rumext.alpha
  (:refer-clojure :exclude [ref deref])
  (:require-macros [rumext.alpha :refer [defc fnc]])
  (:require
   ["react" :as react]
   ["react/jsx-runtime" :as jsxrt]
   ["react-dom/client" :as rdom]
   [cljs.core :as c]
   [goog.functions :as gf]
   [rumext.util :as util]))

(def noop (constantly nil))

(def Component react/Component)
(def Fragment react/Fragment)
(def Profiler react/Profiler)

(defn create-element
  ([type props] (create-element type props nil))
  ([type props children]
   (let [props (js/Object.assign
                (if (nil? children)
                  #js {}
                  (if (array? children)
                    #js {:children children}
                    #js {:children #js [children]}))
                props)
         key   (unchecked-get props "key")]
     (jsxrt/jsxs type props key))))

(defn forward-ref
  [component]
  (react/forwardRef component))

;; --- Impl

(extend-type cljs.core.UUID
  INamed
  (-name [this] (js* "\"\" + ~{}" this))
  (-namespace [_] ""))

;; --- Main Api

(defn create-root
  [node]
  (rdom/createRoot node))

(defn hydrate-root
  [node element]
  (rdom/hydrateRoot node))

(defn mount
  "Add element to the DOM tree. Idempotent. Subsequent mounts will
  just update element."
  [root element]
  (.render ^js root element))

(defn unmount
  "Removes component from the DOM tree."
  [root]
  (.unmount ^js root))

(defn portal
  "Render `element` in a DOM `node` that is ouside of current DOM hierarchy."
  [element node]
  (rdom/createPortal element node))

(defn create-ref
  []
  (react/createRef))

(defn ref-val
  "Given state and ref handle, returns React component."
  [ref]
  (unchecked-get ref "current"))

(defn set-ref-val!
  [ref val]
  (unchecked-set ref "current" val)
  val)

;; --- Context API

(defn create-context
  ([]
   (react/createContext nil))
  ([value]
   (react/createContext value)))

(defn provider
  [ctx]
  (unchecked-get ctx "Provider"))

;; --- Raw Hooks

(defn useRef
  [initial]
  (react/useRef initial))

(defn useState
  [initial]
  (react/useState initial))

(defn useEffect
  [f deps]
  (react/useEffect f deps))

(defn useMemo
  [f deps]
  (react/useMemo f deps))

(defn useCallback
  [f deps]
  (react/useCallback f deps))

(defn useLayoutEffect
  [f deps]
  (react/useLayoutEffect f deps))

(defn useContext
  [ctx]
  (react/useContext ctx))

(defn useTransition
  []
  (react/useTransition))

;; --- Hooks

(defprotocol IDepsAdapter
  (adapt [o] "adapt dep if proceed"))

(extend-protocol IDepsAdapter
  default
  (adapt [o] o)

  cljs.core.UUID
  (adapt [o] (.toString ^js o))

  cljs.core.Keyword
  (adapt [o] (.toString ^js o)))

;; "A convenience function that translates the list of arguments into a
;; valid js array for use in the deps list of hooks.

(defn  deps
  ([] #js [])
  ([a] #js [(adapt a)])
  ([a b] #js [(adapt a) (adapt b)])
  ([a b c] #js [(adapt a) (adapt b) (adapt c)])
  ([a b c d] #js [(adapt a) (adapt b) (adapt c) (adapt d)])
  ([a b c d e] #js [(adapt a) (adapt b) (adapt c) (adapt d) (adapt e)])
  ([a b c d e f] #js [(adapt a) (adapt b) (adapt c) (adapt d) (adapt e) (adapt f)])
  ([a b c d e f g] #js [(adapt a) (adapt b) (adapt c) (adapt d) (adapt e) (adapt f) (adapt g)])
  ([a b c d e f g h] #js [(adapt a) (adapt b) (adapt c) (adapt d) (adapt e) (adapt f) (adapt g) (adapt h)])
  ([a b c d e f g h & rest] (into-array (map adapt (into [a b c d e f g h] rest)))))

(defn use-ref
  ([] (react/useRef nil))
  ([initial] (react/useRef initial)))

(defn use-ctx
  [ctx]
  (react/useContext ctx))

(defn start-transition
  [f]
  (react/startTransition f))

(defn use-effect
  ([f] (use-effect #js [] f))
  ([deps f]
   (useEffect #(let [r (f)] (if (fn? r) r noop)) deps)))

(defn use-layout-effect
  ([f] (use-layout-effect #js [] f))
  ([deps f]
   (useLayoutEffect #(let [r (f)] (if (fn? r) r noop)) deps)))

(defn use-memo
  ([f] (useMemo f #js []))
  ([deps f] (useMemo f deps)))

(defn use-callback
  ([f] (useCallback f #js []))
  ([deps f] (useCallback f deps)))

(defn use-fn
  "A convenient alias to useCallback"
  ([f] (useCallback f #js []))
  ([deps f] (useCallback f deps)))

(defn use-transition
  []
  (let [tmp        (useTransition)
        is-pending (aget tmp 0)
        start-fn   (aget tmp 1)]
    (use-memo
     #js [is-pending]
     (fn []
       (specify! (fn [cb-fn]
                   (^function start-fn cb-fn))
         cljs.core/IDeref
         (-deref [_] is-pending))))))

(defn use-id
  []
  (react/useId))

(defn deref
  [iref]
  (let [state     (use-ref (c/deref iref))
        key       (use-id)
        get-state (use-fn #(unchecked-get state "current"))
        subscribe (use-fn
                   #js [iref]
                   (fn [^function listener-fn]
                     (add-watch iref key (fn [_ _ _ newv]
                                           (unchecked-set state "current" newv)
                                           (listener-fn)))
                     #(remove-watch iref key)))]
    (react/useSyncExternalStore subscribe get-state)))

(defn use-state
  ([] (use-state nil))
  ([initial]
   (let [tmp       (useState initial)
         state     (aget tmp 0)
         update-fn (aget tmp 1)]
     (use-memo
      #js [state]
      (fn []
        (reify
          c/IReset
          (-reset! [_ value]
            (^function update-fn value))

          c/ISwap
          (-swap! [self f]
            (^function update-fn f))
          (-swap! [self f x]
            (^function update-fn #(f % x)))
          (-swap! [self f x y]
            (^function update-fn #(f % x y)))
          (-swap! [self f x y more]
            (^function update-fn #(apply f % x y more)))

          c/IDeref
          (-deref [_] state)))))))

(defn use-var
  "A custom hook for define mutable variables that persists
  on renders (based on useRef hook)."
  ([] (use-var nil))
  ([initial]
   (let [ref (useRef initial)]
     (use-memo
      #js []
      #(specify! (fn [val] (set-ref-val! ref val))
         c/IReset
         (-reset! [_ new-value]
           (set-ref-val! ref new-value))

         c/ISwap
         (-swap!
           ([self f]
            (set-ref-val! ref (f (ref-val ref))))
           ([self f x]
            (set-ref-val! ref (f (ref-val ref) x)))
           ([self f x y]
            (set-ref-val! ref (f (ref-val ref) x y)))
           ([self f x y more]
            (set-ref-val! ref (apply f (ref-val ref) x y more))))

         c/IDeref
         (-deref [self] (ref-val ref)))))))

;; --- Other API

(defn element
  ([klass]
   (create-element klass #js {}))
  ([klass props]
   (let [props (cond
                 (object? props) props
                 (map? props) (util/map->obj props)
                 :else (throw (ex-info "Unexpected props" {:props props})))]
     (create-element klass props))))

;; --- Higher-Order Components

(defn memo'
  "A raw variant of React.memo."
  [component equals?]
  (react/memo component equals?))

(defn memo
  ([component] (react/memo component))
  ([component eq?]
   (react/memo component #(util/props-equals? eq? %1 %2))))

(defn catch
  [component {:keys [fallback on-error]}]
  (let [constructor
        (fn [props]
          (this-as this
            (unchecked-set this "state" #js {})
            (.call Component this props)))

        did-catch
        (fn [error info]
          (when (fn? on-error)
            (on-error error info)))

        derive-state
        (fn [error]
          #js {:error error})

        render
        (fn []
          (this-as this
            (let [state (unchecked-get this "state")
                  props (unchecked-get this "props")
                  error (unchecked-get state "error")]
              (if error
                (react/createElement fallback #js {:error error})
                (react/createElement component props)))))

        _ (goog/inherits constructor Component)
        prototype (unchecked-get constructor "prototype")]

    (unchecked-set constructor "displayName" "ErrorBoundary")
    (unchecked-set constructor "getDerivedStateFromError" derive-state)
    (unchecked-set prototype "componentDidCatch" did-catch)
    (unchecked-set prototype "render" render)
    constructor))

(def ^:private schedule
  (or (and (exists? js/window) js/window.requestAnimationFrame)
      #(js/setTimeout % 16)))

(defn deferred
  ([component] (deferred component schedule))
  ([component sfn]
   (fnc deferred
     {::wrap-props false}
     [props]
     (let [tmp (useState false)
           ^boolean render? (aget tmp 0)
           ^js set-render (aget tmp 1)]
       (use-effect (fn [] (^js sfn #(set-render true))))
       (when render? (create-element component props))))))

(defn throttle
  [component ms]
  (fnc throttle
    {::wrap-props false}
    [props]
    (let [tmp       (useState props)
          state     (aget tmp 0)
          set-state (aget tmp 1)

          ref    (use-ref false)
          render (use-memo #(gf/throttle
                             (fn [v]
                               (when-not (ref-val ref)
                                 (^js set-state v)))
                             ms))]
      (use-effect nil #(render props))
      (use-effect (fn [] #(set-ref-val! ref "true")))
      (create-element component state))))

(defn check-props
  "Utility function to use with `memo'`.
  Will check the `props` keys to see if they are equal.

  Usage:

  (mf/defc my-component
    {::mf/wrap [#(mf/memo' % (checkprops [\"prop1\" \"prop2\"]))]}
    [props]
  )"

  ([props] (check-props props =))
  ([props eqfn?]
   (fn [np op]
     (every? #(eqfn? (unchecked-get np %)
                     (unchecked-get op %))
             props))))
