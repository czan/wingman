;; cider-dont-give-up.el --- Handle and restart exceptions in Clojure. -*- lexical-binding: t; -*-

(require 'cider)

(defvar-local cdgu-restart-request-id nil)
(defvar-local cdgu-restarts nil)

(defun cdgu-nrepl-send-response-to (id response connection &optional tooling)
  (with-current-buffer connection
    (when-let ((session (if tooling nrepl-tooling-session nrepl-session)))
      (setq response (append response `("session" ,session))))
    (let* ((response (cons 'dict response))
           (message (nrepl-bencode response)))
      (nrepl-log-message response 'response)
      (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
      (process-send-string nil message))))

;;;###autoload
(defun cdgu-handle-nrepl-response (response)
  (nrepl-dbind-response response (id type)
    (cond
     ((equal type "restart/prompt")
      (nrepl-dbind-response response (error restarts causes)
        (when restarts
          (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
          (cdgu-prompt-user id error causes restarts))))
     ((equal type "restart/ask")
      (nrepl-dbind-response response (prompt options)
        (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
        (cdgu-ask-user id prompt options (cider-current-connection)))))))

(define-derived-mode cdgu-restart-prompt-mode cider-stacktrace-mode "DGU")

(defun cdgu-choose-restart (&optional index)
  (interactive)
  (let ((choice (or index
                    (ignore-errors (string-to-number (this-command-keys))))))
    (when (<= 1 choice (length cdgu-restarts))
      (cdgu-send-restart-choice cdgu-restart-request-id
                               (1- choice)
                               (cider-current-connection)))))

(defun cdgu-choose-unhandled ()
  (interactive)
  (cdgu-send-restart-choice cdgu-restart-request-id
                           "unhandled"
                           (cider-current-connection)))

(defun cdgu-choose-abort ()
  (interactive)
  (cdgu-send-restart-choice cdgu-restart-request-id
                           "abort"
                           (cider-current-connection)))

(define-key cdgu-restart-prompt-mode-map (kbd "1") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "2") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "3") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "4") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "5") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "6") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "7") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "8") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "9") #'cdgu-choose-restart)
(define-key cdgu-restart-prompt-mode-map (kbd "u") #'cdgu-choose-unhandled)
(define-key cdgu-restart-prompt-mode-map (kbd "q") #'cdgu-choose-abort)

(defun cdgu-send-restart-choice (id restart connection)
  (cdgu-nrepl-send-response-to id
                               `("op" "restart/choose"
                                 "restart" ,restart
                                 "id" ,id)
                               connection)
  (cider-popup-buffer-quit :kill))

(defun cdgu-insert-bounds (&rest args)
  (let ((start (point)))
    (apply #'insert args)
    (cons start (point))))

(defun cdgu-insert-restart-prompt (index name description)
  (insert "  ")
  (let ((clickable-start (point))
        prompt-bounds
        name-bounds)
    (setq prompt-bounds (cdgu-insert-bounds
                         "["
                         (cond
                          ((eq :abort index)
                           "q")
                          ((eq :unhandled index)
                           "u")
                          (:else
                           (number-to-string index)))
                         "]"))
    (insert " ")
    (setq name-bounds (cdgu-insert-bounds name))
    (unless (equal description "")
      (insert " ")
      (cdgu-insert-bounds description))
    (let ((map (make-sparse-keymap)))
      (cond
       ((eq :abort index)
        (define-key map [mouse-2] #'cdgu-choose-abort)
        (define-key map (kbd "<RET>") #'cdgu-choose-abort))
       ((eq :unhandled index)
        (define-key map [mouse-2] #'cdgu-choose-unhandled)
        (define-key map (kbd "<RET>") #'cdgu-choose-unhandled))
       (:else
        (define-key map [mouse-2] (lambda ()
                                    (interactive)
                                    (cdgu-choose-restart index)))
        (define-key map (kbd "<RET>") (lambda ()
                                        (interactive)
                                        (cdgu-choose-restart index)))))
      (add-text-properties clickable-start (point)
                           `(keymap ,map
                                    follow-link t
                                    mouse-face highlight
                                    help-echo "mouse-2: use this restart")))
    (put-text-property (car prompt-bounds) (cdr prompt-bounds)
                       'face 'cider-debug-prompt-face)
    (put-text-property (car name-bounds) (cdr name-bounds)
                       'face 'cider-stacktrace-error-class-face)
    (insert "\n")))

(defun cdgu-prompt-user (id error causes restarts)
  (with-current-buffer (cider-popup-buffer (generate-new-buffer-name "*dgu-prompt*") :select)
    (cdgu-restart-prompt-mode)
    ;; cider-stacktrace relies on this pointing to the right buffer,
    ;; so we just set it right away
    (setq-local cider-error-buffer (current-buffer))
    (let ((inhibit-read-only t)
          error-bounds)
      (cider-stacktrace-render (current-buffer) causes)
      (goto-char (point-min))
      (setq error-bounds (cdgu-insert-bounds error))
      (insert "\n")
      (insert "\n")
      (insert "The following restarts are available:\n")
      (let ((index 1))
        (dolist (restart restarts)
          (cdgu-insert-restart-prompt index (car restart) (cadr restart))
          (setq index (1+ index))))
      (cdgu-insert-restart-prompt :unhandled "unhandled" "Rethrow the exception.")
      (cdgu-insert-restart-prompt :abort "abort" "Abort this evaluation.")
      (insert "\n")
      (goto-char (point-min))
      (when error-bounds
        (put-text-property (car error-bounds) (cdr error-bounds)
                           'face 'cider-stacktrace-error-message-face))
      (setq-local cdgu-restart-request-id id)
      (setq-local cdgu-restarts restarts))))

(defun cdgu-answer (id answer connection)
  (cdgu-nrepl-send-response-to id
                               `("op" "restart/answer"
                                 "input" ,answer
                                 "id" ,id)
                               connection))

(defun cdgu-cancel (id connection)
  (cdgu-nrepl-send-response-to id
                               `("op" "restart/answer"
                                 "error" "cancel"
                                 "id" ,id)
                               connection))

(defun cdgu-read-form (prompt &optional value)
  (cider-read-from-minibuffer prompt value))

(defun cdgu-read-file (prompt &optional value)
  (read-file-name prompt nil value))

(defun cdgu-read-options (prompt &optional options value)
  (completing-read prompt options nil nil value))

(defun cdgu-read-fallback (prompt &rest args)
  (read-string prompt))

(defvar cdgu-prompt-handlers
  `((form . cdgu-read-form)
    (file . cdgu-read-file)
    (options . cdgu-read-options)))

(defun cdgu-ask-user (id prompt options connection)
  (condition-case _
      (nrepl-dbind-response options (type args)
        (let ((handler (alist-get (intern-soft type)
                                  cdgu-prompt-handlers
                                  #'cdgu-read-fallback)))
          (cdgu-answer id (apply handler prompt args) connection)))
    (quit
     (cdgu-cancel id connection))))

;;;###autoload
(define-minor-mode cider-dont-give-up-minor-mode
  "Support nrepl responses from the dont-give-up nrepl
middleware. When an exception occurs, the user will be prompted
to ask how to proceed."
  :global t
  (let ((hook-fn (if cider-dont-give-up-minor-mode
                     #'add-hook
                   #'remove-hook))
        (list-fn (if cider-dont-give-up-minor-mode
                     #'add-to-list
                   #'(lambda (lsym obj)
                       (set lsym (remove obj (symbol-value lsym)))))))
    (funcall hook-fn 'nrepl-response-handler-functions #'cdgu-handle-nrepl-response)
    (funcall list-fn
             'cider-jack-in-dependencies
             '("org.clojars.czan/dont-give-up.nrepl" "0.2.0"))
    (funcall list-fn
             'cider-jack-in-dependencies-exclusions
             '("org.clojars.czan/dont-give-up.nrepl" ("org.clojure/clojure" "org.clojure/tools.nrepl")))
    (funcall list-fn
             'cider-jack-in-nrepl-middlewares
             "dont-give-up.nrepl/middleware")
    (funcall list-fn
             'cider-jack-in-lein-plugins
             '("org.clojars.czan/dont-give-up.nrepl" "0.2.0"))))

;;;###autoload
(with-eval-after-load 'cider
  (cider-dont-give-up-minor-mode 1))
