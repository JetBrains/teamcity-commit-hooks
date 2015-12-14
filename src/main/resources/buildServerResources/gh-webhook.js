BS.GitHubWebHooks = {
    info: {},
    forcePopup: {},
    addWebHook: function (element, type, id, popup) {
        if (document.location.href.indexOf(BS.ServerInfo.url) == -1) {
            if (confirm("Request cannot be processed because browser URL does not correspond to URL specified in TeamCity server configuration: " + BS.ServerInfo.url + ".\n\n" +
                    "Click Ok to redirect to correct URL or click Cancel to leave URL as is.")) {
                var contextPath = BS.RequestInfo.context_path;
                var pathWithoutContext = document.location.pathname;
                if (contextPath.length > 0) {
                    pathWithoutContext = pathWithoutContext.substring(contextPath.length);
                }
                document.location.href = BS.ServerInfo.url + pathWithoutContext + document.location.search + document.location.hash;
            }
            return;
        }
        BS.Log.info("From arguments: " + id + ' ' + type);

        //var progress = $$("# .progress").show();

        var info = BS.GitHubWebHooks.info[type + '_' + id];
        if (info) {
            var repo = info['owner'] + '/' + info['name'];
            if (BS.GitHubWebHooks.forcePopup[repo]) {
                popup = true
            }
        }

        if (popup) {
            BS.Util.popupWindow(window.base_uri + '/oauth/github/add-webhook.html?action=add-popup&id=' + id + '&type=' + type, 'add_webhook_' + type + '_' + id);
            return
        }

        var that = element;

        BS.ProgressPopup.showProgress(element, "Adding WebHook", {shift: {x: -65, y: 20}, zIndex: 100});
        BS.ajaxRequest(window.base_uri + "/oauth/github/add-webhook.html", {
            method: "post",
            parameters: {
                "action": "add",
                "type": type,
                "id": id
            },
            onComplete: function (transport) {
                //progress.hide();
                BS.ProgressPopup.hidePopup(0, true);

                var json = transport.responseJSON;
                if (json['redirect']) {
                    BS.Log.info("Redirect response received");
                    var link = "<a href='#' onclick=\"BS.GitHubWebHooks.addWebHook(this, '" + type + "', '" + id + "', true); return false\">Refresh access token</a>";

                    BS.GitHubWebHooks.showMessage('redirect', 'GitHub authorization needed. ' + link);

                    //BS.Util.popupWindow(json['redirect'], 'add_webhook_' + type + '_' + id);
                    $j(that).append(link);
                    $(that).onclick = function () {
                        BS.GitHubWebHooks.addWebHook(that, '${Type}', '${Id}', true);
                        return false
                    };
                    $(that).innerHTML = "Refresh token and add WebHook"
                } else if (json['error']) {
                    BS.Log.error("Sad :( Something went wrong: " + json['error']);
                    alert(json['error']);
                } else if (json['result']) {
                    var res = json['result'];
                    //if ("TokenScopeMismatch" == res) {
                    //    BS.GitHubWebHooks.showMessage("Token you provided have no access to repository");
                    //    // TODO: Add link to refresh/request token (via popup window)
                    //    that.onclick = function (x) {
                    //        BS.GitHubWebHooks.addWebHook(x, '${Type}', '${Id}', true);
                    //        return false
                    //    };
                    //    //("<a href='#' onclick='BS.GitHubWebHooks.addWebHook(this, '${Type}', '${Id}', false); return false'>Refresh access token</a>");
                    //    that.innerHTML = "Refresh token and add WebHook"
                    //} else {
                    BS.GitHubWebHooks.processResult(json, res);
                    //}
                } else {
                    BS.Log.error("Unexpected response: " + json.toString())
                }
                BS.GitHubWebHooks.refreshReports();
            }
        });
    },

    addConnection: function (element, projectId, serverUrl) {
        document.location.href = window.base_uri + "/admin/editProject.html?projectId=" + projectId + "&tab=oauthConnections#"
    },

    processResult: function (json, res) {
        if ("AlreadyExists" == res) {
            BS.GitHubWebHooks.showMessage(res, "WebHook is already there");
        } else if ("Created" == res) {
            BS.GitHubWebHooks.showMessage(res, "Yay! Successfully created GitHub WebHook");
        } else if ("TokenScopeMismatch" == res) {
            BS.GitHubWebHooks.showMessage(res, "Token you provided have no access to repository, try again", true);
            // TODO: Add link to refresh/request token (via popup window)
            var info = json['info'];
            var repo = info['owner'] + '/' + info['name'];
            BS.GitHubWebHooks.forcePopup[repo] = true
        } else if ("NoAccess" == res) {
            BS.GitHubWebHooks.showMessage(res, "No access to repository", true);
        } else if ("UserHaveNoAccess" == res) {
            BS.GitHubWebHooks.showMessage(res, "You have no access to modify repository hooks", true);
        } else {
            BS.Log.warn("Unexpected result: " + res);
            alert("Unexpected result: " + res);
        }
    },

    callback: function (json) {
        if (json['error']) {
            BS.Log.error("Sad :( Something went wrong: " + json['error']);
            alert(json['error']);
        } else if (json['result']) {
            var res = json['result'];
            BS.GitHubWebHooks.processResult(json, res);
        } else {
            BS.Log.error("Unexpected response: " + json.toString())
        }
        BS.GitHubWebHooks.refreshReports();
    },

    refreshReports: function () {
        var summary = $('reportSummary');
        var categories = $('reportCategories');
        if (summary) {
            summary.refresh();
            categories.refresh();
            return
        }
        var popup = $j('.healthItemIndicator[data-popup]');
        if (popup) {
            BS.Hider.hideDiv(popup.attr('data-popup'));
        }
        //window.location.reload(false)
    },

    showMessage: function (type, text, isWarning) {
        if (typeof isWarning === 'undefined') {
            isWarning = false
        }
        BS.Log.info("Message: " + text);
        var id = "message_tmp_" + type;
        $j('.gh_wh_message').remove();
        if ($(id)) {
            $(id).remove()
        }
        var content = '<div class="gh_wh_message successMessage' + (isWarning ? ' attentionComment' : '') + '" id="' + id + '" style="display: none;">' + text + '</div>';
        var place = $('filterForm');
        if (place) {
            place.insert({'after': content});
        } else {
            place = $('content');
            place.insert({'top': content});
        }
        $(id).show();
        if (!window._shownMessages) window._shownMessages = {};
        window._shownMessages[id] = 'info';
        BS.MultilineProperties.updateVisible();
    }
};


window.GitHubWebHookCallback = BS.GitHubWebHooks.callback;