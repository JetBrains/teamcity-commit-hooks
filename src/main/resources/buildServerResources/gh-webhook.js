BS.GitHubWebHooks = {
    info: {},
    addWebHook: function (type, id) {
        if (document.location.href.indexOf(window.base_uri) == -1) {
            if (confirm("Request cannot be processed because browser URL does not correspond to URL specified in TeamCity server configuration: " + window.base_uri + ".\n\n" +
                    "Click Ok to redirect to correct URL or click Cancel to leave URL as is.")) {
                var contextPath = '';//'${pageContext.request.contextPath}';
                var pathWithoutContext = document.location.pathname;
                if (contextPath.length > 0) {
                    pathWithoutContext = pathWithoutContext.substring(contextPath.length);
                }
                document.location.href = window.base_uri + pathWithoutContext + "?" + document.location.search;
            }
            return;
        }
        BS.Util.popupWindow(window.base_uri + '/oauth/github/add-webhook.html?id=' + id + '&type=' + type, 'add_webhook_' + type + '_' + id);
    },
    callback: function (repo, success) {
        if (success == true) {
            for (var key in this.info) {
                if (this.info.hasOwnProperty(key)) {
                    var value = this.info[key];
                    if (value != null && value.info != null
                        && value.info.server == repo.server
                        && value.info.owner == repo.owner
                        && value.info.name == repo.name) {
                        // Same repo info, lets hide elements
                        // Refresh page ?
                        $('hid_' + key).hide()
                    }
                }
            }
        }
    }
};


window.GitHubWebHookCallback = BS.GitHubWebHooks.callback;