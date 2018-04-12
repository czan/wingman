;; dont-give-up.el --- Handle and restart exceptions in Clojure. -*- lexical-binding: t; -*-

(defvar-local dgu-restart-request-id nil)
(defvar-local dgu-restarts nil)

(defun nrepl-send-response-to (id response connection &optional tooling)
  (with-current-buffer connection
    (when-let ((session (if tooling nrepl-tooling-session nrepl-session)))
      (setq response (append response `("session" ,session))))
    (let* ((response (cons 'dict response))
           (message (nrepl-bencode response)))
      (nrepl-log-message response 'response)
      (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
      (process-send-string nil message))))

(defun dgu-handle-nrepl-response (response)
  (nrepl-dbind-response response (id type)
    (cond
     ((equal type "restart/prompt")
      (nrepl-dbind-response response (error abort restarts causes)
        (when restarts
          (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
          (dgu-prompt-user id error abort causes restarts))))
     ((equal type "restart/ask")
      (nrepl-dbind-response response (prompt)
        (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
        (dgu-ask-user id prompt (cider-current-connection)))))))

(define-derived-mode dgu-restart-prompt-mode cider-stacktrace-mode "DGU")

(defun dgu-choose-restart (&optional index)
  (interactive)
  (let ((choice (or index
                    (ignore-errors (string-to-number (this-command-keys))))))
    (when (<= 1 choice (length dgu-restarts))
      (dgu-send-restart-choice dgu-restart-request-id
                               (1- choice)
                               (cider-current-connection)))))

(defun dgu-choose-abort ()
  (interactive)
  (dgu-send-restart-choice dgu-restart-request-id
                           nil
                           (cider-current-connection)))

(define-key dgu-restart-prompt-mode-map (kbd "1") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "2") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "3") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "4") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "5") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "6") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "7") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "8") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "9") #'dgu-choose-restart)
(define-key dgu-restart-prompt-mode-map (kbd "q") #'dgu-choose-abort)

(defun dgu-send-restart-choice (id restart connection)
  (nrepl-send-response-to id
                          `("op" "restart/choose"
                            "restart" ,restart
                            "id" ,id)
                          connection)
  (cider-popup-buffer-quit :kill))

(defun dgu-insert-bounds (&rest args)
  (let ((start (point)))
    (apply #'insert args)
    (cons start (point))))

(defun dgu-insert-restart-prompt (index name description)
  (insert "  ")
  (let ((clickable-start (point))
        prompt-bounds
        name-bounds
        description-bounds)
    (setq prompt-bounds (dgu-insert-bounds
                         "["
                         (if index
                             (number-to-string index)
                           "q")
                         "]"))
    (insert " ")
    (setq name-bounds (dgu-insert-bounds name))
    (unless (equal description "")
      (insert " - ")
      (setq description-bounds (dgu-insert-bounds description)))
    (let ((map (make-sparse-keymap)))
      (if index
          (progn
            (define-key map [mouse-2] (lambda ()
                                        (interactive)
                                        (dgu-choose-restart index)))
            (define-key map (kbd "<RET>") (lambda ()
                                            (interactive)
                                            (dgu-choose-restart index))))
        (define-key map [mouse-2] #'dgu-choose-abort)
        (define-key map (kbd "<RET>") #'dgu-choose-abort))
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

(defun dgu-prompt-user (id error abort causes restarts)
  (with-current-buffer (cider-popup-buffer (generate-new-buffer-name "*dgu-prompt*") :select)
    (dgu-restart-prompt-mode)
    ;; cider-stacktrace relies on this pointing to the right buffer,
    ;; so we just set it right away
    (setq-local cider-error-buffer (current-buffer))
    (let ((inhibit-read-only t)
          error-bounds)
      (cider-stacktrace-render (current-buffer) causes)
      (goto-char (point-min))
      (setq error-bounds (dgu-insert-bounds error))
      (insert "\n")
      (insert "\n")
      (insert "The following restarts are available:\n")
      (let ((index 1))
        (mapcar (lambda (restart)
                  (dgu-insert-restart-prompt index (car restart) (cadr restart))
                  (setq index (1+ index)))
                restarts))
      (dgu-insert-restart-prompt nil "abort" (or abort "Rethrow the exception."))
      (insert "\n")
      (insert "----------------------\n")
      (goto-char (point-min))
      (when error-bounds
        (put-text-property (car error-bounds) (cdr error-bounds)
                           'face 'cider-stacktrace-error-message-face))
      (setq-local dgu-restart-request-id id)
      (setq-local dgu-restarts restarts))))

(defun dgu-ask-user (id prompt connection)
  (message "%s"(type-of prompt))
  (nrepl-send-response-to id
                          `("op" "restart/answer"
                            "input" ,(read-string prompt)
                            "id" ,id)
                          connection))

(add-hook 'nrepl-response-handler-functions #'dgu-handle-nrepl-response)
