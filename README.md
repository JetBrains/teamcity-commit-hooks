[![official JetBrains project](http://jb.gg/badges/official-plastic.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) [![plugin status]( 
http://teamcity.jetbrains.com/app/rest/builds/buildType:TeamCityPluginsByJetBrains_TeamcityCommitHooks_Build,pinned:true/statusIcon.svg)](https://teamcity.jetbrains.com/viewLog.html?buildTypeId=TeamCityPluginsByJetBrains_TeamcityCommitHooks_Build&buildId=lastPinned&guest=1)

# TeamCity Commit Hooks Plugin 


This plugin allows installing GitHub webhooks for GitHub repositories used by TeamCity VCS roots. At the moment the plugin does three things:
* it shows a suggestion to install a GitHub webhook if it finds a GitHub repository in a project without such a webhook
* it provides a new action in the project 'Actions' menu for webhook installation enabling you to install or reinstall a webhook at any time
* it checks the status of all of the installed webhooks and raises a warning via the health report if some problem is detected

The plugin also installs webhook automatically when a build configuration is created via a URL or GitHub integration and uses a repository from GitHub Enterprise. 

The plugin is compatible with TeamCity 10.0 or later.

# Download

You can download the plugin build and install it as an [additional TeamCity plugin](https://confluence.jetbrains.com/display/TCDL/Installing+Additional+Plugins).

| Download | Compatibility |
|----------|---------------|
| [Download](https://teamcity.jetbrains.com/repository/download/TeamCityPluginsByJetBrains_TeamcityCommitHooks_Build/.lastPinned/teamcity-commit-hooks.zip?guest=1) | TeamCity 10+ |


# Bugs

Found a bug? File [an issue](https://youtrack.jetbrains.com/newIssue?project=TW&clearDraft=true&c=Subsystem+plugins%3A+other).
