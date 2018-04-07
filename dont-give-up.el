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
  (nrepl-dbind-response response (id error detail restarts)
    (when restarts
      (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
      (dgu-prompt-user id error detail restarts))))

(define-derived-mode dgu-restart-prompt-mode special-mode "DGU")

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
                          `("op" "choose-restart"
                            "restart" ,restart
                            "id" ,id)
                          connection)
  (cider-popup-buffer-quit :kill))

(defun dgu-prompt-user (id error detail restarts)
  (with-current-buffer (cider-popup-buffer "*dgu-prompt*" :select)
    (dgu-restart-prompt-mode)
    (let ((inhibit-read-only t))
      (delete-region (point-min) (point-max))
      (insert error)
      (insert "\n")
      (insert "\n")
      (insert "The following restarts are available:\n")
      (let ((index 1))
        (mapcar (lambda (restart)
                  (insert "  [" (number-to-string index) "] ")
                  (insert (car restart))
                  (unless (equal (cadr restart) "")
                    (insert " - ")
                    (insert (cadr restart)))
                  (insert "\n")
                  (setq index (1+ index)))
                restarts))
      (insert "  [q] abort - Rethrow the exception.")
      (insert "\n\n")
      (insert "----------------------\n")
      (insert detail)
      (insert "\n")
      (goto-char (point-min))
      (setq-local dgu-restart-request-id id)
      (setq-local dgu-restarts restarts))))

(add-hook 'nrepl-response-handler-functions #'dgu-handle-nrepl-response)
