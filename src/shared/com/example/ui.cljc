(ns com.example.ui
  (:require
    [com.example.model.account :as acct]
    [com.example.model.address :as address]
    [com.example.ui.login-dialog :refer [LoginForm]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]
       :cljs [com.fulcrologic.fulcro.dom :as dom :refer [div label input]])
    [com.fulcrologic.fulcro.routing.dynamic-routing :refer [defrouter]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.controller :as controller]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls]
    [com.fulcrologic.rad.report :as report]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]))

;; NOTE: Limitation: Each "storage location" requires a form. The ident of the component matches the identity
;; of the item being edited. Thus, if you want to edit things that are related to a given entity, you must create
;; another form entity to stand in for it so that its ident is represented.  This allows us to use proper normalized
;; data in forms when "mixing" server side "entities/tables/documents".
(form/defsc-form AddressForm [this props]
  {::form/id                address/id
   ::form/attributes        [address/street address/city address/state address/zip]
   ::form/enumeration-order {::address/state (sort-by #(get address/states %) (keys address/states))}
   ::form/cancel-route      ["landing-page"]
   ::form/route-prefix      "address"
   ::form/title             "Edit Address"
   ::form/layout            [[::address/street]
                             [::address/city ::address/state ::address/zip]]})

(form/defsc-form AccountForm [this props]
  {::form/id           acct/id
   ::form/attributes   [acct/name acct/email acct/active? acct/addresses]
   ::form/read-only?   {::acct/email true}
   ::form/cancel-route ["landing-page"]
   ::form/route-prefix "account"
   ::form/title        "Edit Account"
   ;; NOTE: any form can be used as a subform, but when you do so you must add addl config here
   ;; so that computed props can be sent to the form to modify its layout. Subforms, for example,
   ;; don't get top-level controls like "Save" and "Cancel".
   ::form/subforms     {::acct/addresses {::form/ui              AddressForm
                                          ::form/can-delete-row? (fn [parent item] (< 1 (count (::acct/addresses parent))))
                                          ::form/can-add-row?    (fn [parent] true)
                                          ::form/add-row-title   "Add Address"
                                          ;; Use computed props to inform subform of its role.
                                          ::form/subform-style   :inline}}})

(defsc AccountListItem [this {::acct/keys [id name active? last-login] :as props}]
  {::report/columns         [::acct/name ::acct/active? ::acct/last-login]
   ::report/column-headings ["Name" "Active?" "Last Login"]
   ::report/row-actions     {:delete (fn [this id] (form/delete! this ::acct/id id))}
   ::report/edit-form       AccountForm
   :query                   [::acct/id ::acct/name ::acct/active? ::acct/last-login]
   :ident                   ::acct/id}
  #_(dom/div :.item
      (dom/i :.large.github.middle.aligned.icon)
      (div :.content
        (dom/a :.header {:onClick (fn [] (form/edit! this AccountForm id))} name)
        (dom/div :.description
          (str (if active? "Active" "Inactive") ". Last logged in " last-login)))))

(def ui-account-list-item (comp/factory AccountListItem {:keyfn ::acct/id}))

(report/defsc-report AccountList [this props]
  {::report/BodyItem         AccountListItem
   ::report/source-attribute ::acct/all-accounts
   ::report/parameters       {:ui/show-inactive? :boolean}
   ::report/route            "accounts"})


(defn StringBufferedInput
  "Create a new type of input that can be derived from a string. `kw` is a fully-qualified keyword name for the new
  class, and model->string and string->model are functions that can do the conversions (and MUST tolerate nil as input).
  `model->string` MUST return a string (empty if invalid), and `string->model` can return nil if invalid.
  "
  [kw {:keys [model->string string->model]}]
  (let [cls (fn [])]
    (comp/configure-component! cls kw
      {:initLocalState (fn [this]
                         (let [{:keys [value]} (comp/props this)]
                           {:lastPropsValue value
                            :stringValue    (model->string value)}))
       :getDerivedStateFromProps
                       (fn [latest-props state]
                         (let [{:keys [value]} latest-props
                               {:keys [oldPropValue stringValue]} state
                               ignorePropValue?  (= oldPropValue value)
                               stringValue       (if ignorePropValue?
                                                   stringValue
                                                   (model->string value))
                               new-derived-state (merge state {:stringValue stringValue})]
                           #js {"fulcro$state" new-derived-state}))
       :render         (fn [this]
                         (let [{:keys [value onChange onBlur] :as props} (comp/props this)
                               {:keys [stringValue]} (comp/get-state this)]
                           (dom/create-element "input" (clj->js
                                                         (merge props
                                                           (cond->
                                                             {:value    stringValue
                                                              :type     "text"
                                                              :onChange (fn [evt]
                                                                          (let [nsv (evt/target-value evt)
                                                                                nv  (string->model nsv)]
                                                                            (comp/set-state! this {:stringValue  nsv
                                                                                                   :oldPropValue value
                                                                                                   :value        nv})
                                                                            (when (and onChange (not= value nv))
                                                                              (onChange nv))))}
                                                             onBlur (assoc :onBlur (fn [evt]
                                                                                     (onBlur (-> evt evt/target-value string->model))))))))))})
    cls))

(def ui-keyword-input (comp/factory (StringBufferedInput ::KeywordInput {:model->string #(str (some-> % name))
                                                                         :string->model #(some-> % keyword)})))

(defsc LandingPage [this {:keys [current]}]
  {:query         [:current]
   :ident         (fn [] [:component/id ::LandingPage])
   :initial-state {:current :x}
   :route-segment ["landing-page"]}
  (dom/div :.ui.form
    (dom/button :.ui.button {:onClick (fn [] (m/set-value! this :current :x))} "Set!")
    (div :.ui.field
      (dom/label "Try ME!")
      (log/info "Render page" current)
      (ui-keyword-input {:value    current
                         :onBlur   (fn [k] (log/info "blur" k))
                         :onChange (fn [k]
                                     (log/info "set" k)
                                     (m/set-value! this :current k))}))))

;; This will just be a normal router...but there can be many of them.
(defrouter MainRouter [this props]
  {:router-targets [LandingPage AccountList AccountForm]})

(def ui-main-router (comp/factory MainRouter))

(auth/defauthenticator Authenticator {:local LoginForm})

(def ui-authenticator (comp/factory Authenticator))

(defsc Root [this {:keys [authenticator router]}]
  {:query         [{:authenticator (comp/get-query Authenticator)}
                   {:router (comp/get-query MainRouter)}]
   :initial-state {:router        {}
                   :authenticator {}}}
  (dom/div
    (div :.ui.top.menu
      (div :.ui.item "Demo Application")
      ;; TODO: Show how we can check authority to hide UI
      (dom/a :.ui.item {:onClick (fn [] (form/edit! this AccountForm (new-uuid 1)))} "My Account")
      (dom/a :.ui.item {:onClick (fn []
                                   (form/delete! this :com.example.model.account/id (new-uuid 2)))}
        "Delete account 2")
      (dom/a :.ui.item {:onClick (fn []
                                   (controller/route-to! this :main-controller ["accounts"]))} "List Accounts"))
    (div :.ui.container.segment
      (ui-authenticator authenticator)
      (ui-main-router router))))

(def ui-root (comp/factory Root))

