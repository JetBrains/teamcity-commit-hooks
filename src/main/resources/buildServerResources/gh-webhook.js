BS.GitHubWebHooks = {};
(function (WH) {
    WH.info = {};
    WH.data = {};
    WH.forcePopup = {};

    function onActionSuccessBasic(json, resource) {
        var info = json['info'];
        var message = json['message'];
        var repo = info['owner'] + '/' + info['name'];
        var server = info['server'];
        var warning = false;
        if ("TokenScopeMismatch" == resource) {
            message = "Token you provided have no access to repository '" + repo + "', try again";
            warning = true;
            // TODO: Add link to refresh/request token (via popup window)
            WH.forcePopup[server] = true
        } else if ("NoAccess" == resource) {
            warning = true;
        } else if ("UserHaveNoAccess" == resource) {
            warning = true;
        } else if ("NoOAuthConnections" == resource) {
            // TODO: Add link to configure connections, good UI.
            warning = true;
        } else if ("Error" == resource) {
            warning = true;
        } else {
            BS.Log.error("Unexpected result: " + resource);
            warning = true;
        }
        var group = server + '/' + repo;
        BS.Util.Messages.show(group, message, warning ? {verbosity: 'warn'} : {});
        return true;
    }

    function onActionSuccess(json, resource, good) {
        var info = json['info'];
        var message = json['message'];
        var server = info['server'];
        var warning = false;
        if (good.indexOf(resource) > -1) {
            // Good one
            const data = json['data'];
            if (data !== undefined) {
                var repository = data['repository'];
                WH.data[repository] = data;
                renderOne(data, $j('#webHooksTable'))
            }
            var group = server + '/' + info['owner'] + '/' + info['name'];
            BS.Util.Messages.show(group, message, warning ? {verbosity: 'warn'} : {});
            return
        }
        onActionSuccessBasic(json, resource);
    }

    WH.actions = {
        add: {
            id: "add",
            name: "Add",
            progress: "Adding WebHook",
            success: function (json, resource) {
                onActionSuccess(json, resource, ["AlreadyExists", "Created"]);
            }
        },
        check: {
            id: "check",
            name: "Check",
            progress: "Checking WebHook",
            success: function (json, resource) {
                onActionSuccess(json, resource, ["Ok"]);
            }
        },
        ping: {
            id: "ping",
            name: "Resend 'ping' payload",
            progress: "Asking GitHub to send ping event",
            success: function (json, resource) {
                onActionSuccess(json, resource, ["Ok"]);
            }
        },
        delete: {
            id: "delete",
            name: "Delete",
            progress: "Deleting WebHook",
            success: function (json, resource) {
                onActionSuccess(json, resource, ["Removed", "NeverExisted"]);
            }
        },
        connect: {
            id: "connect",
            name: "Connect",
            progress: "????CONNECT????"
        }
    };
    WH.checkLocation = function () {
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
            return false;
        }
        return true;
    };
    WH.doWebHookAction = function (action, element, type, id, popup, projectId) {
        // if (!WH.checkLocation()) return;
        //var progress = $$("# .progress").show();

        // Enforce popup for server if needed
        var server = undefined;
        var info = WH.info[id];
        if (info) {
            server = info['server'];
        } else if (type == "repository") {
            server = WH.getServerUrl(id);
        }
        if (server && WH.forcePopup[server]) {
            popup = true
        }

        if (popup) {
            var url = window.base_uri + '/oauth/github/webhooks.html?action=' + action.id + '&popup=true&id=' + id + '&type=' + type;
            if (projectId !== undefined) {
                url = url + "&projectId=" + projectId
            }
            BS.Util.popupWindow(url, 'webhook_' + action.id + '_' + type + '_' + id);
            return
        }

        var that = element;

        // TODO: Proper message
        BS.ProgressPopup.showProgress(element, action.progress, {shift: {x: -65, y: 20}, zIndex: 100});
        var parameters = {
            "action": action.id,
            "type": type,
            "id": id,
            "popup": popup,
        };
        if (projectId !== undefined) {
            parameters["projectId"] = projectId
        }
        //noinspection JSUnusedGlobalSymbols
        BS.ajaxRequest(window.base_uri + "/oauth/github/webhooks.html", {
            method: "post",
            parameters: parameters,
            onComplete: function (transport) {
                //progress.hide();
                BS.ProgressPopup.hidePopup(0, true);

                var json = transport.responseJSON;
                if (json['redirect']) {
                    BS.Log.info("Redirect response received");
                    var link = "<a href='#' onclick=\"BS.GitHubWebHooks.doAction('" + action.id + "', this, '" + id + "','" + projectId + "', true); return false\">Refresh access token and " + action.name + " WebHook</a>";
                    BS.Util.Messages.show(id, 'GitHub authorization needed. ' + link);
                    //BS.Util.popupWindow(json['redirect'], 'add_webhook_' + type + '_' + id);
                    $j(that).append(link);
                    $(that).onclick = function () {
                        WH.doWebHookAction(action, that, type, id, true, projectId);
                        return false
                    };
                    BS.Log.info($(that).onclick);
                    // FIXME: Investigate why text not changed
                    $j(that).text("Refresh token and add WebHook");
                    BS.Log.info($(that).innerHTML);
                } else if (json['error']) {
                    BS.Log.error("Sad :( Something went wrong: " + json['error']);
                } else if (json['result']) {
                    var res = json['result'];
                    //if ("TokenScopeMismatch" == res) {
                    //    WH.showMessage("Token you provided have no access to repository");
                    //    // TODO: Add link to refresh/request token (via popup window)
                    //    that.onclick = function (x) {
                    //        WH.addWebHook(x, '${Type}', '${Id}', true);
                    //        return false
                    //    };
                    //    //("<a href='#' onclick='BS.GitHubWebHooks.addWebHook(this, '${Type}', '${Id}', false); return false'>Refresh access token</a>");
                    //    that.innerHTML = "Refresh token and add WebHook"
                    //} else {
                    WH.processResult(json, res);
                    //}
                } else {
                    BS.Log.error("Unexpected response: " + json.toString())
                }
                WH.refreshReports();
            }
        });
    };

    WH.addConnection = function (element, projectId, serverUrl) {
        document.location.href = window.base_uri + "/admin/editProject.html?projectId=" + projectId + "&tab=oauthConnections#"
    };

    WH.processResult = function (json, res) {
        var action = WH.actions[json['action']];
        if (action) {
            if (action.success) {
                return action.success(json, res)
            }
            BS.Log.warn("There no 'success' function defined for action '" + action.id + "'");
            return "There no 'success' function defined for action '" + action.id + "'"
        }
        BS.Log.warn("Unknown action type: " + json['action']);
    };

    WH.callback = function (json) {
        if (json['error']) {
            BS.Log.error("Sad :( Something went wrong: " + json['error']);
            // Todo: show popup dialog with rich HTML instead of alert
            alert(json['error']);
        } else if (json['result']) {
            var res = json['result'];
            WH.processResult(json, res);
        } else {
            BS.Log.error("Unexpected response: " + JSON.stringify(json))
        }
        WH.refreshReports();
    };

    WH.refreshReports = function () {
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
    };

    WH.getServerUrl = function (repository) {
        var s = String(repository);
        return s.substring(0, s.lastIndexOf("/", s.lastIndexOf("/") - 1));
    };

    WH.doAction = function (name, element, repository, projectId, popup) {
        var action = WH.actions[name.toLowerCase()];
        if (!action) {
            BS.Log.error("Unknown action: " + name);
            return false;
        }
        var p;
        if (repository === undefined) {
            var data_holder = $j(element).parents("[data-repository]");
            repository = data_holder.attr('data-repository');
            projectId = data_holder.attr('data-project-id');
        }
        if (popup === undefined) {
            var fp = WH.forcePopup[WH.getServerUrl(repository)];
            if (fp === undefined) fp = true;
            p = fp
        } else {
            p = popup
        }
        WH.doWebHookAction(action, element, "repository", repository, p, projectId);
        return false;
    };
    WH.checkAll = function (element, projectId, recursive) {
        if (recursive === undefined) recursive = false;
        var parameters = {
            'action': 'check-all',
            'recursive': recursive
        };
        if (projectId) {
            parameters["projectId"] = projectId
        }
        BS.ProgressPopup.showProgress(element, "Rechecking all webhooks", {shift: {x: -65, y: 20}, zIndex: 100});
        BS.ajaxRequest(window.base_uri + "/oauth/github/webhooks.html", {
            method: "post",
            parameters: parameters,
            onComplete: function (transport) {
                BS.ProgressPopup.hidePopup(0, true);
                if (transport.status != 200) {
                    BS.Log.error("Check all responded with " + transport.status);
                    return
                }
                var json = transport.responseJSON;
                if (json['error']) {
                    BS.Log.error("Sad :( Something went wrong: " + json['error']);
                } else if (json['data']) {
                    const table = $j('#webHooksTable');
                    var data = json['data'];
                    for (var i = 0; i < data.length; i++) {
                        var r = data[i];
                        const repo = r['repository'];
                        if (r['user_action_required']) {
                            BS.Log.info("Some user action required to check '" + repo + "' repository.");
                            // TODO: Add link to manually check webhook (popup required)
                            // TODO: Prevent automatic updates, that would hide error (if any)
                            WH.data[repo] = r;
                            WH.data[repo]['manual'] = true;
                            WH.forcePopup[WH.getServerUrl(repo)] = true;
                        } else if (r['result']) {
                            // Operation succeed or failed, at least there's some connections/tokens
                            BS.Log.info("Action either succeed of failed for '" + repo + "'.");
                            WH.data[repo] = r;
                            // TODO: Prevent automatic updates, that would hide error (if any)
                            WH.data[repo]['manual'] = true;
                            // TODO: Do something
                        } else {
                            BS.Log.warn("Action done nothing to '" + repo + "'. Most probably there not connection for that server.");
                            // TODO: Do something
                        }
                        renderOne(r, table)
                    }
                    // TODO: Incremental update
                } else {
                    BS.Log.error("Unexpected response: " + json.toString())
                }
                WH.refreshReports();
            }
        })
    };

    function getStatusClass(status) {
        switch (status) {
            case "NO_INFO":
                return "no-info";
            case "NOT_FOUND":
                return "not-found";
            case "OK":
                return "good";
            case "WAITING_FOR_SERVER_RESPONSE":
                return "pending";
            case "INCORRECT":
                return "error";
            default:
                return "";
        }
    }

    function getStatusPresentation(status) {
        switch (status) {
            case "NO_INFO":
                return "No information";
            case "NOT_FOUND":
                return "Not found";
            case "OK":
                return "OK";
            case "WAITING_FOR_SERVER_RESPONSE":
                return "Waiting for ping event";
            case "INCORRECT":
                return "Incorrect";
            default:
                return status;
        }
    }

    function getStatusDiv(status, error) {
        if (error !== undefined) {
            return '<div class="webhook-status err" data-status="error">Error <a href="#" onclick="BS.GitHubWebHooks.more(this); return false">details</a></div>' +
                '<div style="display: none">' + error + '</div>'
        }
        return '<div class="webhook-status ' + getStatusClass(status) + '" data-status="' + status + '">' + getStatusPresentation(status) + '</div>'
    }

    function getLinkHtml(repository, hook) {
        if (hook == null) return "";
        return '<a href="//' + repository + '/settings/hooks/' + hook['id'] + '">View on GitHub</a>';
    }

    function getActionsHtml(actions) {
        return actions.map(function (action) {
            return '<div><a href="#" onclick="BS.GitHubWebHooks.doAction(\'' + action + '\', this); return false;">' + action + '</a></div>'
        }).join("");
    }

    function renderOne(data, table) {
        var repository = data['repository']; // string

        var line = $j(table).find("tr[data-repository='" + repository + "']");
        if (line.length == 0) {
            BS.Log.warn("Line not found for repository " + repository);
            return;
        }

        var error = data['error']; // String?
        var info = data['info']; // VcsRootGitHubInfo?
        var hook = data['hook']; // HookInfo?
        var status = data['status']; // String?
        var actions = data['actions']; // List<String>?
        line.find("[data-view=status]").html(getStatusDiv(status, error));
        line.find("[data-view=actions]").html(getActionsHtml(actions));
        line.find("[data-view=link]").html(getLinkHtml(repository, hook));
    }

    WH.refresh = function (element, repositories, table) {
        if (repositories === undefined) {
            if (table !== undefined) {
                var data_holders = $j(table).find("[data-repository]");
                repositories = data_holders.map(function () {
                    return $j(this).attr('data-repository');
                }).toArray();
            } else if (element !== undefined) {
                var data_holder = $j(element).parents("[data-repository]");
                repositories = [data_holder.attr('data-repository')];
            } else return;
        }
        if (repositories.length < 1) return;
        if (table === undefined) {
            table = $j('#webHooksTable');
        }
        if (element !== undefined) {
            BS.ProgressPopup.showProgress(element, "Refreshing webhook" + (repositories.length > 1 ? 's' : ''), {shift: {x: -65, y: 20}, zIndex: 100});
        } else if (table !== undefined) {
            $j(table).find('.spinner').show();
        }
        BS.ajaxRequest(window.base_uri + "/oauth/github/webhooks.html", {
            method: 'post',
            parameters: {
                'action': 'get-info',
                'repository': repositories
            },
            onComplete: function (transport) {
                if (element !== undefined) {
                    BS.ProgressPopup.hidePopup(0, true);
                } else if (table !== undefined) {
                    $j(table).find('.spinner').hide();
                }
                if (transport.status != 200) {
                    BS.Log.error("Fetching webhooks info responded with " + transport.status);
                    return
                }
                var json = transport.responseJSON;
                if (json['error']) {
                    BS.Log.error("Sad :( Something went wrong: " + json['error']);
                } else if (json['result']) {
                    var arr = json['result'];
                    // Update internal data structure, then re-render table
                    for (var i = 0; i < arr.length; i++) {
                        var r = arr[i];
                        var repository = r['repository'];
                        if (WH.data[repository] && WH.data[repository]['manual']) {
                            if (!element) {
                                continue
                            }
                            WH.data[repository]['manual'] = false;
                        }
                        WH.data[repository] = r;
                    }
                    WH.renderTable($j(table));
                } else {
                    BS.Log.error("Unexpected response: " + json.toString())
                }
                WH.refreshReports();
            }
        })
    };

    WH.refreshTable = function (table) {
        WH.refresh(undefined, undefined, table)
    };

    WH.renderTable = function (table) {
        for (var k in WH.data) {
            if (!WH.data.hasOwnProperty(k)) continue;
            renderOne(WH.data[k], table);
        }
    };

    WH.more = function (element) {
        const text = $j(element).parent().next().text();
        BS.WarningPopup.showWarning(element, {x: -65, y: 20}, text);
    };

    WH.setVcsPoolInterval = function (element, vcsRootId, projectId) {
        // TODO: Proper message
        BS.ProgressPopup.showProgress(element, "Updating Changes Checking Interval", {shift: {x: -65, y: 20}, zIndex: 100});
        BS.ajaxRequest(window.base_uri + "/oauth/github/webhooks.html", {
            method: "post",
            parameters: {
                "action": "set-cci",
                "vcsRootId": vcsRootId,
                "projectId": projectId
            },
            onComplete: function (transport) {
                BS.ProgressPopup.hidePopup(0, true);
                var json = transport.responseJSON;
                if (json['error']) {
                    BS.Log.error("Sad :( Something went wrong: " + json['error']);
                    BS.Util.Messages.show("vcs-roots", json['error'], {verbosity: 'warn'});
                } else if (json['message']) {
                    BS.Util.Messages.show("vcs-roots", json['message'], {verbosity: 'warn'});
                } else {
                    BS.Log.error("Unexpected response: " + json.toString())
                }
                WH.refreshReports();
            }
        });
    }
})(BS.GitHubWebHooks);

BS.Util.Messages = {};
(function (Messages) {
    Messages.show = function (group, text, options) {
        group = group.replace(/([:/\.])/g, '_');
        if (options === undefined) options = {};
        options = $j.extend({}, options, {
            verbosity: 'info', // Either 'info' or 'warn'
            group: 'messages_group_' + group,
            id: 'message_id_' + group
        });

        BS.Log.info("Message: " + text);

        // Hide previous messages from the same group, id
        BS.Util.Messages.hide({class: options.class});
        BS.Util.Messages.hide({id: options.id});
        // if (options.verbosity == 'info') {
        //     BS.Util.Messages.hide({verbosity: options.verbosity});
        // }

        // TODO: Use node manipulations instead of html code generation (?) Note: message may contain html tags
        var content = '<div data-message-group="' + options.group + '" data-message-verbosity="' + options.verbosity + '" class="successMessage' + (options.verbosity == 'warn' ? ' attentionComment' : '') + '" id="' + options.id + '" style="display: none;"><a href="#" class="attentionDismiss" onclick="return BS.Util.Messages.close(this);">X</a>' + text + '</div>';
        var place = $('filterForm');
        if (place) {
            place.insert({'after': content});
        } else {
            place = $('content');
            place.insert({'top': content});
        }
        $(options.id).show();
        if (!window._shownMessages) window._shownMessages = {};
        window._shownMessages[options.id] = options.verbosity;

        // Why?
        BS.MultilineProperties.updateVisible();
    };
    Messages.close = function (element) {
        $j(element).parents('div[data-message-group]').remove();
        return false;
    };
    function hide(list) {
        if (list === undefined || list == null || list.length == 0) return;
        list.each(function () {
            if (window._shownMessages && window._shownMessages[this.id]) {
                delete window._shownMessages[this.id];
            }
        });
        list.remove();
    }

    Messages.hide = function (options) {
        if (options.id !== undefined) {
            hide($j($(options.id)))
        }
        if (options.group !== undefined) {
            hide($j('div[data-message-group=\'' + options.group + '\''))
        }
        if (options.verbosity !== undefined) {
            hide($j('div[data-message-verbosity=\'' + options.verbosity + '\''))
        }
    };

})(BS.Util.Messages);

BS.AdminActions = {};
(function (AA) {
    AA.toggleVcsRootInstanceUsages = function (link, vcsRootInstanceId) {
        $j('#instance_' + vcsRootInstanceId + '_usages').toggle();
        var parent = $j(link).parent().toggleClass("usageHl");
        parent.parent().find(".vcsRoot").toggleClass("bold");
        return false;
    };
    AA.toggleWebHookUsages = function (link, id) {
        $j('#webhook_' + id + '_usages').toggle();
        var parent = $j(link).parent().toggleClass("usageHl");
        parent.parent().find(".webHook").toggleClass("bold");
        return false;
    };
})(BS.AdminActions);


window.GitHubWebHookCallback = BS.GitHubWebHooks.callback;