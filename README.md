[![official JetBrains project](https://jb.gg/badges/official-plastic.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) [![plugin status]( 
https://teamcity.jetbrains.com/app/rest/builds/buildType:TeamCityPluginsByJetBrains_TeamcityCommitHooks_Build,pinned:true/statusIcon.svg)](https://teamcity.jetbrains.com/viewLog.html?buildTypeId=TeamCityPluginsByJetBrains_TeamcityCommitHooks_BuildTeamCity20172x&buildId=lastPinned&guest=1)

# TeamCity Commit Hooks Plugin 


This plugin allows installing GitHub webhooks for GitHub repositories used by TeamCity VCS roots. At the moment the plugin does three things:
* it shows a suggestion to install a GitHub webhook if it finds a GitHub repository in a project without such a webhook
* it provides a new action in the project 'Actions' menu for webhook installation enabling you to install or reinstall a webhook at any time
* it checks the status of all of the installed webhooks and raises a warning via the health report if some problem is detected

The plugin also installs webhook automatically when a build configuration is created via a URL or GitHub integration and uses a repository from GitHub Enterprise. 

The latest version of the plugin is compatible with TeamCity version 2017.2 or higher. 

# Download

You can download the plugin build and install it as an [additional TeamCity plugin](https://www.jetbrains.com/help/teamcity/installing-additional-plugins.html).

| Download | Compatibility |
|----------|---------------|
| [Download](https://plugins.jetbrains.com/plugin/9179-github-commit-hooks) | TeamCity 2017.2+ |


# Bugs

Found a bug? File [an issue](https://youtrack.jetbrains.com/newIssue?project=TW&clearDraft=true&c=Subsystem+plugins%3A+other).
