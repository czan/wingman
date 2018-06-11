;;; cider-wingman.el --- Handle and restart exceptions in Clojure. -*- lexical-binding: t; -*-

;; Copyright (C) 2018 Carlo Zancanaro

;; Author: Carlo Zancanaro <carlo@zancanaro.id.au>
;; Maintainer: Carlo Zancanaro <carlo@zancanaro.id.au>
;; Created: 12 Jun 2018
;; Keywords: cider clojure wingman exception
;; Homepage: https://github.com/czan/wingman

;; This file is not part of GNU Emacs.

;;; The MIT License:

;; Copyright (c) 2018 Carlo Zancanaro

;; Permission is hereby granted, free of charge, to any person obtaining a copy
;; of this software and associated documentation files (the "Software"), to deal
;; in the Software without restriction, including without limitation the rights
;; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:

;; The above copyright notice and this permission notice shall be included in all
;; copies or substantial portions of the Software.

;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
;; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
;; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
;; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
;; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
;; SOFTWARE.

;;; Code:

(require 'cider)

(defvar-local cider-wingman-restart-request-id nil)
(defvar-local cider-wingman-restarts nil)

(defun cider-wingman-nrepl-send-response-to (id response connection &optional tooling)
  (with-current-buffer connection
    (when-let ((session (if tooling nrepl-tooling-session nrepl-session)))
      (setq response (append response `("session" ,session))))
    (let* ((response (cons 'dict response))
           (message (nrepl-bencode response)))
      (nrepl-log-message response 'response)
      (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
      (process-send-string nil message))))

;;;###autoload
(defun cider-wingman-handle-nrepl-response (response)
  (nrepl-dbind-response response (id type)
    (cond
     ((equal type "restart/prompt")
      (nrepl-dbind-response response (error restarts causes)
        (when restarts
          (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
          (cider-wingman-prompt-user id error causes restarts))))
     ((equal type "restart/ask")
      (nrepl-dbind-response response (prompt options)
        (puthash id (lambda (&rest ignored)) nrepl-pending-requests)
        (cider-wingman-ask-user id prompt options (cider-current-connection)))))))

(define-derived-mode cider-wingman-restart-prompt-mode cider-stacktrace-mode "Wingman")

(defun cider-wingman-choose-restart (&optional index)
  (interactive)
  (let ((choice (or index
                    (ignore-errors (string-to-number (this-command-keys))))))
    (when (<= 1 choice (length cider-wingman-restarts))
      (cider-wingman-send-restart-choice cider-wingman-restart-request-id
                               (1- choice)
                               (cider-current-connection)))))

(defun cider-wingman-choose-unhandled ()
  (interactive)
  (cider-wingman-send-restart-choice cider-wingman-restart-request-id
                           "unhandled"
                           (cider-current-connection)))

(defun cider-wingman-choose-abort ()
  (interactive)
  (cider-wingman-send-restart-choice cider-wingman-restart-request-id
                           "abort"
                           (cider-current-connection)))

(define-key cider-wingman-restart-prompt-mode-map (kbd "1") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "2") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "3") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "4") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "5") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "6") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "7") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "8") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "9") #'cider-wingman-choose-restart)
(define-key cider-wingman-restart-prompt-mode-map (kbd "u") #'cider-wingman-choose-unhandled)
(define-key cider-wingman-restart-prompt-mode-map (kbd "q") #'cider-wingman-choose-abort)

(defun cider-wingman-send-restart-choice (id restart connection)
  (cider-wingman-nrepl-send-response-to id
                               `("op" "restart/choose"
                                 "restart" ,restart
                                 "id" ,id)
                               connection)
  (cider-popup-buffer-quit :kill))

(defun cider-wingman-insert-bounds (&rest args)
  (let ((start (point)))
    (apply #'insert args)
    (cons start (point))))

(defun cider-wingman-insert-restart-prompt (index name description)
  (insert "  ")
  (let ((clickable-start (point))
        prompt-bounds
        name-bounds)
    (setq prompt-bounds (cider-wingman-insert-bounds
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
    (setq name-bounds (cider-wingman-insert-bounds name))
    (unless (equal description "")
      (insert " ")
      (cider-wingman-insert-bounds description))
    (let ((map (make-sparse-keymap)))
      (cond
       ((eq :abort index)
        (define-key map [mouse-2] #'cider-wingman-choose-abort)
        (define-key map (kbd "<RET>") #'cider-wingman-choose-abort))
       ((eq :unhandled index)
        (define-key map [mouse-2] #'cider-wingman-choose-unhandled)
        (define-key map (kbd "<RET>") #'cider-wingman-choose-unhandled))
       (:else
        (define-key map [mouse-2] (lambda ()
                                    (interactive)
                                    (cider-wingman-choose-restart index)))
        (define-key map (kbd "<RET>") (lambda ()
                                        (interactive)
                                        (cider-wingman-choose-restart index)))))
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

(defun cider-wingman-prompt-user (id error causes restarts)
  (with-current-buffer (cider-popup-buffer (generate-new-buffer-name "*wingman-prompt*") :select)
    (cider-wingman-restart-prompt-mode)
    ;; cider-stacktrace relies on this pointing to the right buffer,
    ;; so we just set it right away
    (setq-local cider-error-buffer (current-buffer))
    (let ((inhibit-read-only t)
          error-bounds)
      (cider-stacktrace-render (current-buffer) causes)
      (goto-char (point-min))
      (setq error-bounds (cider-wingman-insert-bounds error))
      (insert "\n")
      (insert "\n")
      (insert "The following restarts are available:\n")
      (let ((index 1))
        (dolist (restart restarts)
          (cider-wingman-insert-restart-prompt index (car restart) (cadr restart))
          (setq index (1+ index))))
      (cider-wingman-insert-restart-prompt :unhandled "unhandled" "Rethrow the exception.")
      (cider-wingman-insert-restart-prompt :abort "abort" "Abort this evaluation.")
      (insert "\n")
      (goto-char (point-min))
      (when error-bounds
        (put-text-property (car error-bounds) (cdr error-bounds)
                           'face 'cider-stacktrace-error-message-face))
      (setq-local cider-wingman-restart-request-id id)
      (setq-local cider-wingman-restarts restarts))))

(defun cider-wingman-answer (id answer connection)
  (cider-wingman-nrepl-send-response-to id
                               `("op" "restart/answer"
                                 "input" ,answer
                                 "id" ,id)
                               connection))

(defun cider-wingman-cancel (id connection)
  (cider-wingman-nrepl-send-response-to id
                               `("op" "restart/answer"
                                 "error" "cancel"
                                 "id" ,id)
                               connection))

(defun cider-wingman-read-form (prompt &optional value)
  (cider-read-from-minibuffer prompt value))

(defun cider-wingman-read-file (prompt &optional value)
  (read-file-name prompt nil value))

(defun cider-wingman-read-options (prompt &optional options value)
  (completing-read prompt options nil nil value))

(defun cider-wingman-read-fallback (prompt &rest args)
  (read-string prompt))

(defvar cider-wingman-prompt-handlers
  `((form . cider-wingman-read-form)
    (file . cider-wingman-read-file)
    (options . cider-wingman-read-options)))

(defun cider-wingman-ask-user (id prompt options connection)
  (condition-case _
      (nrepl-dbind-response options (type args)
        (let ((handler (alist-get (intern-soft type)
                                  cider-wingman-prompt-handlers
                                  #'cider-wingman-read-fallback)))
          (cider-wingman-answer id (apply handler prompt args) connection)))
    (quit
     (cider-wingman-cancel id connection))))

;;;###autoload
(define-minor-mode cider-wingman-minor-mode
  "Support nrepl responses from the wingman nrepl middleware.
When an exception occurs, the user will be prompted to ask how to
proceed."
  :global t
  (let ((hook-fn (if cider-wingman-minor-mode
                     #'add-hook
                   #'remove-hook))
        (list-fn (if cider-wingman-minor-mode
                     #'add-to-list
                   #'(lambda (lsym obj)
                       (set lsym (remove obj (symbol-value lsym)))))))
    (funcall hook-fn 'nrepl-response-handler-functions #'cider-wingman-handle-nrepl-response)
    (funcall list-fn
             'cider-jack-in-dependencies
             '("wingman/wingman.nrepl" "0.3.1"))
    (funcall list-fn
             'cider-jack-in-dependencies-exclusions
             '("wingman/wingman.nrepl" ("org.clojure/clojure" "org.clojure/tools.nrepl")))
    (funcall list-fn
             'cider-jack-in-nrepl-middlewares
             "wingman.nrepl/middleware")
    (funcall list-fn
             'cider-jack-in-lein-plugins
             '("wingman/wingman.nrepl" "0.3.1"))))

;;;###autoload
(with-eval-after-load 'cider
  (cider-wingman-minor-mode 1))

;;; cider-wingman.el ends here
