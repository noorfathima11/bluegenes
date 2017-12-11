(ns bluegenes.components.idresolver.views
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame :refer [subscribe dispatch]]
            [json-html.core :as json-html]
            [dommy.core :as dommy :refer-macros [sel sel1]]
            [clojure.string :refer [join split]]
            [bluegenes.components.idresolver.events]
            [bluegenes.components.icons :as icons]
            [bluegenes.components.ui.results_preview :refer [preview-table]]
            [bluegenes.components.loader :refer [mini-loader]]
            [bluegenes.components.idresolver.subs]
            [bluegenes.components.imcontrols.views :as im-controls]
            [bluegenes.components.lighttable :as lighttable]
            [bluegenes.events.id-resolver :as evts]
            [bluegenes.subs.id-resolver :as subs]
            [oops.core :refer [oget oget+ ocall]]
            [imcljs.path :as path]))

;;; TODOS:

;We need to handler more than X results :D right now 1000 results would ALL show on screen. Eep.

(def separators (set ",; "))

(def timeout 1500)

(defn organism-selection
  "UI component allowing user to choose which organisms to search. Defaults to all."
  []
  (let [selected-organism (subscribe [:idresolver/selected-organism])]
    [:div [:label "Organism"]
     [im-controls/organism-dropdown
      {:selected-value (if (some? @selected-organism) @selected-organism "Any")
       :on-change (fn [organism]
                    (dispatch [:idresolver/set-selected-organism organism]))}]]))

(defn object-type-selection
  "UI component allowing user to choose which object type to search. Defaults to the first one configured for a mine."
  []
  (let [selected-object-type (subscribe [:idresolver/selected-object-type])
        values               (subscribe [:idresolver/object-types])]
    [:div [:label "Type"]
     [im-controls/object-type-dropdown
      {:values @values
       :selected-value @selected-object-type
       :on-change (fn [object-type]
                    (dispatch [:idresolver/set-selected-object-type object-type]))}]]))

(defn splitter "Splits a string on any one of a set of strings."
  [string]
  (->> (clojure.string/split string (re-pattern (str "[" (reduce str separators) "\\r?\\n]")))
       (remove nil?)
       (remove #(= "" %))))

(defn has-separator?
  "Returns true if a string contains any one of a set of strings."
  [str]
  (some? (some separators str)))

(defn controls []
  (let [results  (subscribe [:idresolver/results])
        matches  (subscribe [:idresolver/results-matches])
        selected (subscribe [:idresolver/selected])]
    (fn []
      [:div.btn-toolbar.controls
       [:button.btn.btn-warning
        {:class (if (nil? @results) "disabled")
         :on-click (fn [] (dispatch [:idresolver/clear]))}
        "Clear all"]
       [:button.btn.btn-warning
        {:class (if (empty? @selected) "disabled")
         :on-click (fn [] (dispatch [:idresolver/delete-selected]))}
        (str "Remove selected (" (count @selected) ")")]
       [:button.btn.btn-primary.btn-raised
        {:disabled (if (empty? @matches) "disabled")
         :on-click (fn [] (if (some? @matches) (dispatch [:idresolver/analyse true])))}
        "View Results"]])))

(defn submit-input
  ([input val]
   (reset! val "")
   (submit-input input))
  ([input]
   (dispatch [:idresolver/resolve (splitter input)]))
  )

(defn input-box []
  (reagent/create-class
    (let [val   (reagent/atom nil)
          timer (reagent/atom nil)]
      {:reagent-render (fn []
                         [:input#identifierinput.freeform
                          {:type "text"
                           :placeholder "Type or paste identifiers here..."
                           :value @val
                           :on-key-press
                           (fn [e]
                             (let [keycode (.-charCode e)
                                   input   (.. e -target -value)]
                               (cond (= keycode 13)
                                     (submit-input input val))))
                           ;;not all keys are picked up by on key press or on-change so we need to do both.
                           :on-change
                           (fn [e]
                             (let [input (.. e -target -value)]
                               ;;we have a counter that automatically submits the typed entry if the user waits long enough (currently 1.5s).
                               ;stop old auto-submit counter.
                               (js/clearInterval @timer)
                               ;start new timer again
                               (reset! timer (js/setTimeout #(submit-input input val) timeout))
                               ;submit the stuff
                               (if (has-separator? input)
                                 (do
                                   (js/clearInterval @timer)
                                   (submit-input input val))
                                 (reset! val input))))}])
       ;;autofocus on the entry field when the page loads
       :component-did-mount (fn [this] (.focus (reagent/dom-node this)))})))

(defn organism-identifier
  "Sometimes the ambiguity we're resolving with duplicate ids is the sme symbol from two similar organisms, so we'll need to ass organism name where known."
  [summary]
  (if (:organism.name summary)
    (str " (" (:organism.name summary) ")")
    ""
    )
  )

(defn build-duplicate-identifier
  "Different objects types have different summary fields. Try to select one intelligently or fall back to primary identifier if the others ain't there."
  [result]
  (let [summary   (:summary result)
        symbol    (:symbol summary)
        accession (:primaryAccession summary)
        primaryId (:primaryId summary)]
    (str
      (first (remove nil? [symbol accession primaryId]))
      (organism-identifier summary)
      )))

(defn input-item-duplicate []
  "Input control. allows user to resolve when an ID has matched more than one object."
  (fn [[oid data]]
    [:span.id-item [:span.dropdown
                    [:span.dropdown-toggle
                     {:type "button"
                      :data-toggle "dropdown"}
                     (:input data)
                     [:span.caret]]
                    (into [:ul.dropdown-menu]
                          (map (fn [result]
                                 [:li {:on-click
                                       (fn [e]
                                         (.preventDefault e)
                                         (dispatch [:idresolver/resolve-duplicate
                                                    (:input data) result]))}
                                  [:a (build-duplicate-identifier result)]]) (:matches data)))]]))

(defn get-icon [icon-type]
  (case icon-type
    :MATCH [:svg.icon.icon-checkmark.MATCH [:use {:xlinkHref "#icon-checkmark"}]]
    :UNRESOLVED [:svg.icon.icon-sad.UNRESOLVED [:use {:xlinkHref "#icon-sad"}]]
    :DUPLICATE [:svg.icon.icon-duplicate.DUPLICATE [:use {:xlinkHref "#icon-duplicate"}]]
    :TYPE_CONVERTED [:svg.icon.icon-converted.TYPE_CONVERTED [:use {:xlinkHref "#icon-converted"}]]
    :OTHER [:svg.icon.icon-arrow-right.OTHER [:use {:xlinkHref "#icon-arrow-right"}]]
    [mini-loader "tiny"]))

(def reasons
  {:TYPE_CONVERTED "we're searching for genes and you input a protein (or vice versa)."
   :OTHER " the synonym you input is out of date."})

(defn input-item-converted [original results]
  (let [new-primary-id    (get-in results [:matches 0 :summary :primaryIdentifier])
        conversion-reason ((:status results) reasons)]
    [:span.id-item {:title (str "You input '" original "', but we converted it to '" new-primary-id "', because " conversion-reason)}
     original " -> " new-primary-id]
    ))

(defn input-item [{:keys [input] :as i}]
  "visually displays items that have been input and have been resolved as known or unknown IDs (or currently are resolving)"
  (let [result   (subscribe [:idresolver/results-item input])
        selected (subscribe [:idresolver/selected])]
    (reagent/create-class
      {:component-did-mount
       (fn [])
       :reagent-render
       (fn [i]
         (let [result-vals (second (first @result))
               class       (if (empty? @result)
                             "inactive"
                             (name (:status result-vals)))
               class       (if (some #{input} @selected) (str class " selected") class)]
           [:div.id-resolver-item-container
            {:class (if (some #{input} @selected) "selected")}
            [:div.id-resolver-item
             {:class class
              :on-click (fn [e]
                          (.preventDefault e)
                          (.stopPropagation e)
                          (dispatch [:idresolver/toggle-selected input]))}
             [get-icon (:status result-vals)]
             (case (:status result-vals)
               :DUPLICATE [input-item-duplicate (first @result)]
               :TYPE_CONVERTED [input-item-converted (:input i) result-vals]
               :OTHER [input-item-converted (:input i) result-vals]
               :MATCH [:span.id-item (:input i)]
               [:span.id-item (:input i)])
             ]]))})))

(defn input-items []
  (let [bank (subscribe [:idresolver/bank])]
    (fn []
      (into [:div.input-items]
            (map (fn [i]
                   ^{:key (:input i)} [input-item i]) (reverse @bank))))))

(defn parse-files [files]
  (dotimes [i (.-length files)]
    (let [rdr      (js/FileReader.)
          the-file (aget files i)]
      (set! (.-onload rdr)
            (fn [e]
              (let [file-content (.-result (.-target e))
                    file-name    (if (= ";;; " (.substr file-content 0 4))
                                   (let [idx (.indexOf file-content "\n\n")]
                                     (.slice file-content 4 idx))
                                   (.-name the-file))]
                (submit-input file-content))))
      (.readAsText rdr the-file))))

(defn handle-drag-over [state-atom evt]
  (reset! state-atom true)
  (.stopPropagation evt)
  (.preventDefault evt)
  (set! (.-dropEffect (.-dataTransfer evt)) "copy"))

(defn handle-drop-over [state-atom evt]
  (reset! state-atom false)
  (.stopPropagation evt)
  (.preventDefault evt)
  (let [files (.-files (.-dataTransfer evt))]
    (parse-files files)))

(defn obj->clj
  "Convert the top level of a javascript object to clojurescript.
  Unconditionally attempts on any js property, unlike js->clj"
  [obj]
  (reduce (fn [m k] (assoc m (keyword k) (oget+ obj k))) {} (js-keys obj)))

(defn bytes->size [bytes]
  (let [sizes ["Bytes" "KB" "MB" "GB" "TB"]]
    (if (= bytes 0)
      "0 bytes"
      (let [idx (js/parseInt (js/Math.floor (/ (js/Math.log bytes) (js/Math.log 1000))))]
        (str (js/Math.round (/ bytes (js/Math.pow 1000 idx)) 2) " " (nth sizes idx))))))

(defn file []
  (fn [js-File]
    (let [f (obj->clj js-File)]
      [:div.file
       [:span.grow (:name f)]
       [:span.shrink
        [:span.file-size (bytes->size (:size f))]
        [:button.btn.btn-default.btn-xs
         {:on-click (fn [] (dispatch [::evts/unstage-file js-File]))}
         [:i.fa.fa-times]]]])))

(defn file-manager []
  (let [files               (subscribe [::subs/staged-files])
        textbox-identifiers (subscribe [::subs/textbox-identifiers])
        stage-options       (subscribe [::subs/stage-options])
        stage-status        (subscribe [::subs/stage-status])
        upload-elem         (reagent/atom nil)]
    (fn []
      [:div.file-manager
       #_[:button.btn.btn-default
          {:on-click (fn [] (dispatch [::evts/parse-staged-files
                                       @files
                                       (:case-sensitive? @stage-options)]))}
          (str "Upload" (when @files (str " " (count @files) " file" (when (> (count @files) 1) "s"))))]
       [:input
        {:type "file"
         :ref (fn [e] (reset! upload-elem e))
         :multiple true
         :style {:display "none"}
         :on-click (fn [e] (.stopPropagation e)) ;;otherwise we just end up focusing on the input on the left/top.
         :on-change (fn [e] (dispatch [::evts/stage-files (oget e :target :files)]))}]
       [:div.form-group
        [:label "or Upload from file(s)"]
        (when @files (into [:div.files] (map (fn [js-File] [file js-File]) @files)))
        [:button.btn.btn-default
         {:on-click (fn [] (-> @upload-elem js/$ (ocall :click)))
          :disabled @textbox-identifiers}
         (if @files "Browse more" "Browse")]]])))



(defn drag-and-drop-prompt []
  (fn []
    [:div.upload-file
     [:svg.icon.icon-file [:use {:xlinkHref "#icon-file"}]]
     [:p "All your identifiers in a text file? Try dragging and dropping it here, or "
      [:label.browseforfile {:on-click (fn [e] (.stopPropagation e))} ;;otherwise it focuses on the typeable input
       [:input
        {:type "file"
         :multiple true
         :on-click (fn [e] (.stopPropagation e)) ;;otherwise we just end up focusing on the input on the left/top.
         :on-change (fn [e] (dispatch [::evts/stage-files (oget e :target :files)]))}]
       ;;this input isn't visible, but don't worry, clicking on the label is still accessible. Even the MDN says it's ok. True story.
       "browse for a file"]]
     [file-manager]]))

(defn input-div []
  (let [drag-state (reagent/atom false)]
    (fn []
      [:div.resolvey
       [:div#dropzone1
        {
         :on-drop (partial handle-drop-over drag-state)
         :on-click (fn [evt]
                     (.preventDefault evt)
                     (.stopPropagation evt)
                     (dispatch [:idresolver/clear-selected])
                     (.focus (sel1 :#identifierinput)))
         :on-drag-over (partial handle-drag-over drag-state)
         :on-drag-leave (fn [] (reset! drag-state false))
         :on-drag-end (fn [] (reset! drag-state false))
         :on-drag-exit (fn [] (reset! drag-state false))}
        [:div.eenput
         {:class (if @drag-state "dragging")}
         [:div.idresolver
          [:div.type-and-organism
           [organism-selection]
           [object-type-selection]]
          [input-items]
          [input-box]
          [controls]]
         [drag-and-drop-prompt]
         ]]
       ])))

(defn stats []
  (let [bank           (subscribe [:idresolver/bank])
        no-matches     (subscribe [:idresolver/results-no-matches])
        matches        (subscribe [:idresolver/results-matches])
        type-converted (subscribe [:idresolver/results-type-converted])
        duplicates     (subscribe [:idresolver/results-duplicates])
        other          (subscribe [:idresolver/results-other])]
    (fn []
      ;;goodness gracious this could use a refactor
      [:div.legend
       [:h3 "Legend & Stats:"]
       [:div.results
        [:div.MATCH {:tab-index -5}
         [:div.type-head [get-icon :MATCH]
          [:span.title "Matches"]
          [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
         [:div.details [:span.count (count @matches)]
          [:p "The input you entered was successfully matched to a known ID"]
          ]
         ]
        [:div.TYPE_CONVERTED {:tab-index -4}
         [:div.type-head [get-icon :TYPE_CONVERTED]
          [:span.title "Converted"]
          [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
         [:div.details [:span.count (count @type-converted)]
          [:p "Input protein IDs resolved to gene (or vice versa)"]]
         ]
        [:div.OTHER {:tab-index -2}
         [:div.type-head [get-icon :OTHER]
          [:span.title "Synonyms"]
          [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
         [:div.details [:span.count (count @other)]
          [:p "The ID you input matches an old synonym of an ID. We've used the most up-to-date one instead."]]]
        [:div.DUPLICATE {:tab-index -3}
         [:div.type-head [get-icon :DUPLICATE]
          [:span.title "Partial\u00A0Match"]
          [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
         [:div.details [:span.count (count @duplicates)]
          [:p "The ID you input matched more than one item. Click on the down arrow beside IDs with this icon to fix this."]]]
        [:div.UNRESOLVED {:tab-index -1}
         [:div.type-head [get-icon :UNRESOLVED]
          [:span.title "Not\u00A0Found"]
          [:svg.icon.icon-question [:use {:xlinkHref "#icon-question"}]]]
         [:div.details [:span.count (count @no-matches)]
          [:p "The ID provided isn't one that's known for your chosen organism"]]]
        ]]

      )))

(defn debugger []
  (let [everything (subscribe [:idresolver/everything])]
    (fn []
      [:div (json-html/edn->hiccup @everything)])))

(defn selected []
  (let [selected (subscribe [:idresolver/selected])]
    (fn []
      [:div "selected: " (str @selected)])))

(defn delete-selected-handler [e]
  (let [keycode (.-charCode e)]
    (cond
      (= keycode 127) (dispatch [:idresolver/delete-selected])
      :else nil)))

(defn key-down-handler [e]
  (case (.. e -keyIdentifier)
    "Control" (dispatch [:idresolver/toggle-select-multi true])
    "Shift" (dispatch [:idresolver/toggle-select-range true])
    nil))

(defn key-up-handler [e]
  (case (.. e -keyIdentifier)
    "Control" (dispatch [:idresolver/toggle-select-multi false])
    "Shift" (dispatch [:idresolver/toggle-select-range false])
    nil))

(defn attach-body-events []

  (dommy/unlisten! (sel1 :body) :keypress delete-selected-handler)
  (dommy/listen! (sel1 :body) :keypress delete-selected-handler)

  (dommy/unlisten! (sel1 :body) :keydown key-down-handler)
  (dommy/listen! (sel1 :body) :keydown key-down-handler)

  (dommy/unlisten! (sel1 :body) :keyup key-up-handler)
  (dommy/listen! (sel1 :body) :keyup key-up-handler))

(defn preview [result-count]
  (let [results-preview   (subscribe [:idresolver/results-preview])
        fetching-preview? (subscribe [:idresolver/fetching-preview?])]
    [:div
     [:h4.title "Results preview:"
      [:small.pull-right "Showing " [:span.count (min 5 result-count)] " of " [:span.count result-count] " Total Good Identifiers. "
       (cond (> result-count 0)
             [:a {:on-click
                  (fn [] (dispatch [:idresolver/analyse true]))}
              "View all >>"])
       ]]
     [preview-table
      :loading? @fetching-preview?
      :query-results @results-preview]]
    ))


(defn select-organism-option []
  (fn [organism]
    [:div.form-group
     [:label "Organism"]
     [im-controls/select-organism
      {:value organism
       :on-change (fn [organism] (dispatch [::evts/update-option :organism organism]))}]]))

(defn select-type-option []
  (fn [type]
    [:div.form-group
     [:label "List type"]
     [im-controls/select-type
      {:value type
       :on-change (fn [type] (dispatch [::evts/update-option :type type]))}]]))

(defn case-sensitive-option []
  (fn [bool]
    [:div.form-group
     [:label ""]
     [:div.checkbox
      [:label
       [:input
        {:type "checkbox"
         :checked bool
         :on-change (fn [e]
                      (dispatch [::evts/update-option :case-sensitive (oget e :target :checked)]))}]
       [:div "My identifiers are case sensitive"]]]]))


(defn upload-step []
  (let [files               (subscribe [::subs/staged-files])
        textbox-identifiers (subscribe [::subs/textbox-identifiers])
        options             (subscribe [::subs/stage-options])]
    (fn []
      (let [{:keys [organism type case-sensitive]} @options]
        [:div
         [:h3 "Create a new list"]
         [:div.alert.alert-info
          [:p "Select the type of list to create and either enter in a list of identifiers or upload identifiers from a file. A search will be performed for all the identifiers in your list."]]
         [:div.row
          [:div.col-sm-4 [select-type-option type]]
          [:div.col-sm-4 [select-organism-option organism]]
          [:div.col-sm-4 [case-sensitive-option case-sensitive]]]
         [:div.upload-step-container
          [:div.input-area
           [:div.vertical-divider]
           [:div.freeform-container
            [:div.form-group
             {:style {:height "100%"}}
             [:label "Enter identifiers"]
             [:textarea.form-control
              {:on-change (fn [e] (dispatch [::evts/update-textbox-identifiers (oget e :target :value)]))
               :value @textbox-identifiers
               :class (when @files "disabled")
               :disabled @files
               :style {:height "100%"} :rows 5}]]]
           [:div.file-container [file-manager]]]]
         [:button.btn.btn-primary.pull-right
          {:on-click (fn [] (dispatch [::evts/parse-staged-files @files @options]))}
          (str "Upload" (when @files (str " " (count @files) " file" (when (> (count @files) 1) "s"))))
          [:i.fa.fa-chevron-right {:style {:padding-left "5px"}}]]]))))


(defn review-table []
  (let [pager          (reagent/atom {:show 5 :page 0})
        summary-fields (subscribe [:current-summary-fields])
        model          (subscribe [:current-model])]
    (fn [type results]
      (let [pages               (Math/floor (/ (count results) (:show @pager)))
            rows-in-view        (take (:show @pager) (drop (* (:show @pager) (:page @pager)) results))
            type-summary-fields (get @summary-fields (keyword type))]
        [:div.form-inline.clearfix

         [:div.form-group
          [:div.btn-toolbar
           [:button.btn.btn-default
            {:on-click (fn [] (swap! pager update :page (comp (partial max 0) dec)))}
            [:i.fa.fa-chevron-left]]
           [:button.btn.btn-default
            {:on-click (fn [] (swap! pager update :page (comp (partial min pages) inc)))}
            [:i.fa.fa-chevron-right]]]]

         [:div.clearfix
          [:div.form-group.pull-right
           (into [:select.form-control
                  {:on-change (fn [e] (swap! pager assoc :page (oget e :target :value)))
                   :value (:page @pager)}]
                 (map (fn [p]
                        [:option {:value p} (str "Page " (inc p))]) (range pages)))
           [:label {:style {:margin-left "5px"}} (str "of " pages)]]]

         [:table.table.table-condensed.table-striped
          {:style {:background-color "white"}}
          [:thead [:tr [:th {:row-span 2} "Your Identifier"] [:th {:col-span 6} "Matches"]]
           (into
             [:tr [:th "Keep?"]]
             (map
               (fn [f]
                 (let [path (path/friendly @model f)]
                   [:th (join " > " (rest (split path " > ")))])) type-summary-fields))]
          (into [:tbody]
                (->> rows-in-view
                     (map-indexed
                       (fn [duplicate-idx {:keys [input reason matches] :as duplicate}]
                         (->> matches
                              (map-indexed
                                (fn [match-idx {:keys [summary keep?] :as match}]
                                  (into
                                    (if (= match-idx 0)
                                      [:tr {:class (when keep? "success")}
                                       [:td {:row-span (count matches)} input]]
                                      [:tr {:class (when keep? "success")}])
                                    (conj (map
                                            (fn [field]
                                              (let [without-prefix (keyword (join "." (rest (split field "."))))]
                                                [:td (get summary without-prefix)])) type-summary-fields)
                                          [:td
                                           [:div.checkbox
                                            [:label
                                             [:input
                                              {:type "checkbox"
                                               :checked keep?
                                               :on-change (fn [e]
                                                            (dispatch [::evts/toggle-keep-duplicate
                                                                       duplicate-idx match-idx]))}]]]])))))))
                     (apply concat)))]]))))

(defn review-step []
  (let [resolution-response (subscribe [::subs/resolution-response])
        list-name           (subscribe [::subs/list-name])]
    (fn []
      (let [{{{:keys [matches issues notFound all]} :identifiers :as s} :stats} @resolution-response]
        [:div
         [:div.flex-progressbar
          [:div.bar.bar-success {:style {:flex (* 100 (/ matches all))}} (str matches " Matches")]
          [:div.bar.bar-warning {:style {:flex (* 100 (/ issues all))}} (str issues " Issues")]
          [:div.bar.bar-danger {:style {:flex (* 100 (/ notFound all))}} (str notFound " Not Found")]]
         [:div.alert.alert-warning.clearfix
          [:div.row
           [:div.col-sm-12
            [:h3 [:i.fa.fa-exclamation-triangle {:style {:margin-right "10px"}}] (str issues " identifiers returned duplicate results")]
            [:p "Please select from the list of duplicates which ones you want to keep"]]]
          [:div.row
           [:div.col-sm-12
            [:div.form-group
             [:label "List Name"]
             [:input.form-control {:type "text"
                                   :value @list-name
                                   :on-change (fn [e] (dispatch [::evts/update-list-name (oget e :target :value)]))}]]]]
          [:button.btn.btn-success.pull-right
           {:on-click (fn [] (dispatch [::evts/save-list]))} "Save List"
           [:i.fa.fa-chevron-right {:style {:padding-left "5px"}}]]]
         [review-table (:type @resolution-response) (-> @resolution-response :matches :DUPLICATE)]]))))

(defn bread-circles []
  (let [response (subscribe [::resolution-response])]
    (fn [view]
      [:ul.bread
       [:li {:class (when (or (= view nil) (= view :upload)) "active")
             :on-click (fn [] (dispatch [::evts/set-view nil]))} [:a [:i.fa.fa-upload.fa-1x] "Upload"]]
       [:li.disabled {:class (when (= view :review) "active")
                      :on-click (fn [] (dispatch [::evts/set-view :review]))} [:a [:i.fa.fa-exclamation-triangle.fa-1x] "Review"]]])))

(defn bread []
  (let [response (subscribe [::subs/resolution-response])]
    (fn [view]
      [:h4 [:ol.breadcrumb {:style {:padding "8px 15px"}}
            [:li {:class (when (or (= view nil) (= view :upload)) "active")
                  :on-click (fn [] (dispatch [::evts/set-view nil]))}
             [:a [:i.fa.fa-upload.fa-1x] " Upload"]]
            [:li.disabled {:class (when (= view :review) "active")
                           :on-click (fn [] (when @response (dispatch [::evts/set-view :review])))}
             (if @response
               [:a [:i.fa.fa-exclamation-triangle.fa-1x] " Review"]
               [:span [:i.fa.fa-exclamation-triangle.fa-1x] " Review"])]]])))

(defn wizard []
  (let [view (subscribe [::subs/view])]
    (fn []
      [:div.wizard
       [bread @view]
       [:div.wizard-body.clearfix
        (case @view
          :review [review-step]
          [upload-step])]
       [:div.wizard-footer
        [:div.grow]
        [:div.shrink]]])))


(defn main []
  (reagent/create-class
    {:component-did-mount attach-body-events
     :reagent-render
     (fn []
       (let [bank         (subscribe [:idresolver/bank])
             no-matches   (subscribe [:idresolver/results-no-matches])
             result-count (- (count @bank) (count @no-matches))]
         [:div.container.idresolverupload
          #_[:div.headerwithguidance
             [:a.guidance
              {:on-click
               (fn []
                 (dispatch [:idresolver/example splitter]))} "[Show me an example]"]]
          [wizard]
          #_[input-div]
          ;[stats]
          (cond (> result-count 0) [preview result-count])
          ;[selected]
          ;[debugger]
          ]))}))
