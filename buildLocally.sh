plugin_name="teamcity-commit-hooks"
assembled_plugin="build/distributions/${plugin_name}"
web_deployment_debug=""
wd="external-repos/${plugin_name}/"

pwd

cd $wd
./gradlew -Dorg.gradle.daemon=false -b build.gradle clean build -x test

if [ -e "${assembled_plugin}.zip" ]
then
  echo "commit hooks plugin is built"
  rm -rf "$assembled_plugin"
  unzip -d "$assembled_plugin" "${assembled_plugin}.zip"
  rm -rf "../../.idea_artifacts/web_deployment_debug/WEB-INF/plugins/${plugin_name}"
  cp -R "$assembled_plugin" "../../.idea_artifacts/web_deployment_debug/WEB-INF/plugins/"
  echo "plugin is installed"
else
  echo "error: commit hooks plugin is not built"
fi

cd ../..