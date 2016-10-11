#Teamcity Commit Hooks Plugin 
[![plugin status]( 
http://teamcity.jetbrains.com/app/rest/builds/buildType:TeamCityPluginsByJetBrains_TeamcityCommitHooks_Build,pinned:true/statusIcon.svg)](https://teamcity.jetbrains.com/viewLog.html?buildTypeId=TeamCityPluginsByJetBrains_TeamcityCommitHooks_Build&buildId=lastPinned) 

This plugin allows installing GitHub webhooks for GitHub repositories used by TeamCity VCS roots. At the moment the plugin does three things:
* it shows a suggestion to install a GitHub webhook if it finds a GitHub repository in a project without such a webhook
* it provides a new action in the project 'Actions' menu for webhook installation enabling you to install or reinstall a webhook at any time
* it checks the status of all of the installed webhooks and raises a warning via the health report if some problem is detected

The plugin also installs webhook automatically when a build configuration is created via a URL or GitHub integration and uses a repository from GitHub Enterprise. 

The plugin is compatible with TeamCity 10.0 or later.

#Download
[TeamCity 10.0](https://teamcity.jetbrains.com/viewLog.html?buildTypeId=TeamCityPluginsByJetBrains_TeamcityCommitHooks_Build&buildId=lastPinned&tab=artifacts) 

#License

Apache 2.0

#Bugs

Found a bug? File [an issue](https://youtrack.jetbrains.com/newIssue?project=TW&clearDraft=true&c=Subsystem+plugins%3A+other).

