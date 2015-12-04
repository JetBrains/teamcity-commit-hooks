BS.GitHubWebHooks = {
    info: {},
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
        var id2 = element.parentElement.parentElement.attributes["data-id"];
        var type2 = element.parentElement.parentElement.attributes["data-type"];

        BS.Log.info("From arguments: " + id + ' ' + type);
        BS.Log.info("From data-x: " + id2 + ' ' + type2);

        //var progress = $$("# .progress").show();

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
                    BS.Log.info("GitHub authorization needed. Opening special page.");
                    //BS.Util.popupWindow(json['redirect'], 'add_webhook_' + type + '_' + id);
                    $j(that).append("<a href='#' onclick=\"BS.GitHubWebHooks.addWebHook(this, '" + type + "', '" + id + "', true); return false\">Refresh access token</a>");
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
                    if ("AlreadyExists" == res) {
                        BS.Log.info("WebHook is already there");
                    } else if ("Created" == res) {
                        BS.Log.info("Yay! Successfully created GitHub WebHook");
                    } else if ("TokenScopeFailure" == res) {
                        BS.Log.info("Token you provided have no access to repository");
                        // TODO: Add link to refresh/request token (via popup window)
                        that.onclick = function (x) {
                            BS.GitHubWebHooks.addWebHook(x, '${Type}', '${Id}', true);
                            return false
                        };
                        //("<a href='#' onclick='BS.GitHubWebHooks.addWebHook(this, '${Type}', '${Id}', false); return false'>Refresh access token</a>");
                        that.innerHTML = "Refresh token and add WebHook"
                    } else if ("NoAccess" == res) {
                        BS.Log.info("No access to repository");
                    } else if ("UserNoAccess" == res) {
                        BS.Log.info("You have no access to modify repository hooks");
                        that.disabled = true
                    } else {
                        BS.Log.warn("Unexpected result: " + res);
                        alert("Unexpected result: " + res);
                    }
                } else {
                    BS.Log.error("Unexpected response: " + json.toString())
                }
                // TODO: refresh bs:refreshable with health items
                // window.location.reload(true)
            }
        });
    },

    addConnection: function (element, projectId, serverUrl) {
        document.location.href = window.base_uri + "/admin/editProject.html?projectId=" + projectId + "&tab=oauthConnections#"
    },

    callback: function (json) {
        if (json['error']) {
            BS.Log.error("Sad :( Something went wrong: " + json['error']);
            alert(json['error']);
        } else if (json['result']) {
            var res = json['result'];
            if ("AlreadyExists" == res) {
                BS.Log.info("WebHook is already there");
            } else if ("Created" == res) {
                BS.Log.info("Yay! Successfully created GitHub WebHook");
            } else if ("TokenScopeFailure" == res) {
                BS.Log.info("Token you provided have no access to repository");
                // TODO: Add link to refresh/request token (via popup window)
            } else if ("NoAccess" == res) {
                BS.Log.info("No access to repository");
            } else if ("UserNoAccess" == res) {
                BS.Log.info("You have no access to modify repository hooks");
            } else {
                BS.Log.warn("Unexpected result: " + res);
                alert("Unexpected result: " + res);
            }
        } else {
            BS.Log.error("Unexpected response: " + json.toString())
        }
        // TODO: refresh bs:refreshable with health items
        // window.location.reload(true)
    }
};


window.GitHubWebHookCallback = BS.GitHubWebHooks.callback;