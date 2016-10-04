BS.GitHubWebHooks = {};
(function (WH) {
    WH.info = {};
    WH.data = {};
    WH.forcePopup = {};

    WH.SIGN_IN_BUTTON_TITLE = "Sign in to GitHub";
    WH.INSTALL_BUTTON_TITLE = "Install webhook";
    WH.WEBHOOKS_CONTROLLER_PATH = "/webhooks/github/webhooks.html";

    function onActionSuccessBasic(json, result) {
        var info = json['info'];
        var message = json['message'];
        var repo = info['owner'] + '/' + info['name'];
        var server = info['server'];
        var error = false;
        if ("NotFound" == result) {
            error = true;
        } else if ("TokenScopeMismatch" == result) {
            message = "Token scope does not allow to install webhook to the repository '" + repo + "'";
            error = true;
            // TODO: Add link to refresh/request token (via popup window)
            WH.forcePopup[server] = true
        } else if ("NoAccess" == result) {
            error = true;
        } else if ("Moved" == result) {
            error = true;
        } else if ("InternalServerError" == result) {
        } else if ("UserHaveNoAccess" == result) {
            error = true;
        } else if ("NoOAuthConnections" == result) {
            // TODO: Add link to configure connections, good UI.
            error = true;
        } else if ("Error" == result) {
            error = true;
        } else {
            BS.Log.error("Unexpected result: " + result);
            error = true;
        }

        if (error) {
            WH.showError(message);
        } else {
            WH.showSuccessMessage(message);
        }
    }

    function onActionSuccess(json, result, good) {
        var info = json['info'];
        var message = json['message'];
        var server = info['server'];
        if (good.indexOf(result) > -1) {
            // Good one
            const data = json['data'];
            if (data !== undefined) {
                var repository = data['repository'];
                WH.data[repository] = data;
                renderOne(data, $j('#webHooksTable'))
            }
            WH.showSuccessMessage(message);
            return
        }
        onActionSuccessBasic(json, result);
    }

    var BaseAction = {
        doHandleRedirect: function (json, id, element) {
            WH.forcePopup[WH.getServerUrl(id)] = true;
            var actionId = this.id;
            var text = "Refresh access token and " + this.name + " webhook";

            var link = "<a href='#' onclick=\"return BS.GitHubWebHooks.doAction('" + actionId + "', this)\">" + text + "</a>";

            BS.Util.Messages.hide({group: 'gh_wh_install'});
            BS.Util.Messages.show(id, 'GitHub authorization needed. ' + link);
            $j(element).append(link);
            $(element).onclick = function () {
                return WH.doAction(actionId, element);
            };
            $j(element).html(text);
        },
        doShowProgress: function (element) {
            BS.ProgressPopup.showProgress(element, this.progress, {shift: {x: -65, y: 20}, zIndex: 100});
        },
        doHideProgress: function () {
            BS.ProgressPopup.hidePopup(0, true);
        }
    };

    WH.actions = {
        add: OO.extend(BaseAction, {
            id: "add",
            name: "Add",
            progress: "Adding Webhook",
            doHandleResult: function (json, result) {
                onActionSuccess(json, result, ["AlreadyExists", "Created"]);
            }
        }),
        check: OO.extend(BaseAction, {
            id: "check",
            name: "Check",
            progress: "Checking Webhook",
            doHandleResult: function (json, result) {
                onActionSuccess(json, result, ["Ok"]);
            }
        }),
        ping: OO.extend(BaseAction, {
            id: "ping",
            name: "Resend 'push' payload",
            progress: "Asking GitHub to send push event",
            doHandleResult: function (json, result) {
                onActionSuccess(json, result, ["Ok"]);
            }
        }),
        delete: OO.extend(BaseAction, {
            id: "delete",
            name: "Delete",
            progress: "Deleting Webhook",
            doHandleResult: function (json, result) {
                onActionSuccess(json, result, ["Removed", "NeverExisted"]);
            }
        }),
        install: OO.extend(BaseAction, {
            "id": "install",
            name: "Install",
            progress: "Installing Webhook",
            doHandleResult: function (json, result) {
                this.doHideProgress();
                var good = ["AlreadyExists", "Created"];
                var info = json['info'];
                var message = json['message'];
                var repo = info['owner'] + '/' + info['name'];
                var server = info['server'];

                if (good.indexOf(result) > -1) {
                    // Good one
                    const data = json['data'];
                    if (data !== undefined) {
                        var repository = data['repository'];
                        WH.data[repository] = data;
                    }
                    WH.forcePopup[server] = false;
                    $j("#installWebhookSubmit").attr("value", WH.INSTALL_BUTTON_TITLE);
                    WH.showSuccessMessage(message);
                    return
                }
                if ("TokenScopeMismatch" == result) {
                    message = "Token scope does not allow to install webhook to the repository '" + repo + "'";
                    $j("#installWebhookSubmit").attr("value", WH.SIGN_IN_BUTTON_TITLE);
                    WH.forcePopup[server] = true;
                    WH.showError(message);
                    return
                }
                onActionSuccessBasic(json, result);
            },
            doHandleRedirect: function (json, id) {
                WH.forcePopup[WH.getServerUrl(id)] = true;

                $j("#installWebhookSubmit").attr("value", WH.SIGN_IN_BUTTON_TITLE);
                WH.showError("Please sign in to GitHub to proceed.");
            },
            doHandleError: function (json) {
                this.doHideProgress();
                var error = json['error'];
                WH.showError(error);
            },
            doShowProgress: function () {
                BS.Util.show('installProgress');
                $j('#installButton').attr('disabled', true);
            },
            doHideProgress: function () {
                BS.Util.hide('installProgress');
                $j('#installButton').removeAttr('disabled', false)
            }
        })
    };

    function isSameServer(first, second) {
        if (!first || !second) return false;
        return first.indexOf(second) > -1
    }

    WH.doWebHookAction = function (action, element, id, popup, projectId) {
        // Enforce popup for server if needed
        var server = undefined;
        var info = WH.info[id];
        if (info) {
            server = info['server'];
        } else {
            server = WH.getServerUrl(id);
        }
        if (server && WH.forcePopup[server]) {
            popup = true
        }

        if (popup) {
            var url = BS.ServerInfo.url + WH.WEBHOOKS_CONTROLLER_PATH + '?action=' + action.id + '&popup=true&id=' + id;
            if (projectId !== undefined) {
                url = url + "&projectId=" + projectId
            }
            var popupWin = BS.Util.popupWindow(url, 'webhook_' + action.id + '_' + id);
            var interval = window.setInterval(function() {
              try {
                if (popupWin == null || popupWin.closed) {
                  window.clearInterval(interval);
                  $j("#installWebhookSubmit").attr("value", WH.INSTALL_BUTTON_TITLE);
                  WH.forcePopup[server] = false;
                  WH.doInstallForm($('installWebhookSubmit'));
                }
              } catch (e) {
              }
            }, 1000);
            return;
        }

        var that = element;

        action.doShowProgress(element);

        var parameters = {
            "action": action.id,
            "id": id,
            "popup": popup
        };
        if (projectId !== undefined) {
            parameters["projectId"] = projectId
        }
        var data_holder = $j(element).parents("[data-connection-id]");
        if (data_holder) {
            var conn_server = data_holder.attr('data-connection-server');
            if (isSameServer(server, conn_server)) {
                parameters["connectionId"] = data_holder.attr('data-connection-id');
                parameters["connectionProjectId"] = data_holder.attr('data-connection-project-id');
            }
        }
        //noinspection JSUnusedGlobalSymbols
        BS.ajaxRequest(window.base_uri + WH.WEBHOOKS_CONTROLLER_PATH, {
            method: "post",
            parameters: parameters,
            onComplete: function (transport) {
                var json = transport.responseJSON;
                var action = WH.actions[json['action']] || action;

                action.doHideProgress();

                if (json['redirect']) {
                    BS.Log.info("Redirect response received");
                    action.doHandleRedirect(json, id, that)
                } else {
                    WH.doHandle(json, action)
                }
                WH.refreshReports();
            }
        });
    };

    WH.doHandle = function (json, action) {
        if (json['error']) {
            if (action && action.doHandleError) {
                action.doHandleError(json)
            } else {
                BS.Log.error("Something went wrong: " + json['error']);
                WH.showError(json['error']);
            }
        } else if (json['result']) {
            var res = json['result'];
            if (action) {
                return action.doHandleResult(json, res)
            }
            BS.Log.warn("Unknown action: " + json['action']);
        } else {
            BS.Log.error("Unexpected response: " + JSON.stringify(json))
        }
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
            var server = WH.getServerUrl(repository);
            if (!server) {
                // Seems input is incorrect, do not show popup
                p = false;
            } else {
                var fp = WH.forcePopup[server];
                if (fp === undefined) fp = false;
                p = fp;
            }
        } else {
            p = popup
        }
        WH.doWebHookAction(action, element, repository, p, projectId);
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
        BS.ajaxRequest(window.base_uri + WH.WEBHOOKS_CONTROLLER_PATH, {
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
                        WH.data[repo] = r;
                        if (r['user_action_required']) {
                            WH.data[repo].warning = r['error'];
                            WH.forcePopup[getServerUrl(repo)] = true;
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
        var warning = data['warning']; // boolean?
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
        BS.ajaxRequest(window.base_uri + WH.WEBHOOKS_CONTROLLER_PATH, {
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

    WH.doInstallForm = function (element) {
        var repository = $j('input#repository').val();
        var projectId = $j('input#projectId').val();

        this.clearMessages();

        if (!repository || repository.length == 0) {
            this.showError("Repository URL is not specified");
            return false;
        }

        return WH.doAction('install', element, repository, projectId);
    };

    WH.showError = function(text) {
        WH.clearMessages();
        $j('#webhookError').html(text).show();
    };

    WH.showSuccessMessage = function(text) {
        WH.clearMessages();
        $j('#webhookMessage').html(text).show();
    };

    WH.clearMessages = function() {
        $j('.error').text("").hide();
        $j('#webhookMessage').text("").hide();
    };
})(BS.GitHubWebHooks);

BS.Util.Messages = BS.Util.Messages || {};
(function (Messages) {
    Messages.show = function (group, text, options) {
        group = group.replace(/([:/\.])/g, '_');
        if (options === undefined) options = {};
        options = $j.extend({}, {
            verbosity: 'info', // Either 'info' or 'warn'
            group: 'messages_group_' + group,
            id: 'message_id_' + group
        }, options);

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

BS.AdminActions = BS.AdminActions || {};
(function (AA) {
    AA.toggleVcsRootInstanceUsages = function (link, vcsRootInstanceId) {
        $j('#instance_' + vcsRootInstanceId + '_usages').toggle();
        var parent = $j(link).parent().toggleClass("usageHl");
        parent.find(".vcsRoot").toggleClass("bold");
        return false;
    };
    AA.toggleWebHookUsages = function (link, id) {
        $j('#webhook_' + id + '_usages').toggle();
        var parent = $j(link).parent().toggleClass("usageHl");
        parent.parent().find(".webHook").toggleClass("bold");
        return false;
    };
})(BS.AdminActions);
