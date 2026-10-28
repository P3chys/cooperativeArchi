# Windows PowerShell, not pwsh: PowerShell 7 from the Microsoft Store runs in an app sandbox that redirects writes to
# %APPDATA% (where Archi keeps dropins and settings) to a private copy, so Archi would never see what we install
set windows-shell := ["powershell.exe", "-NoLogo", "-NoProfile", "-Command"]

gw := if os_family() == "windows" { '.\gradlew.bat' } else { "./gradlew" }

# list recipes
default:
    @just --list --unsorted

# core tests (pass -ParchiHome=... to validate merges against another Archi)
test *args:
    {{gw}} :core:test {{args}}

# build the installable .archiplugin into build/dist (target: Archi line, 5.4 or 5.10)
plugin target="5.10":
    {{gw}} :plugin:archiplugin "-ParchiTarget={{target}}"

# build the .archiplugin for every supported Archi line (release files)
dist:
    {{gw}} :plugin:archiplugin "-ParchiTarget=5.4"
    {{gw}} :plugin:archiplugin "-ParchiTarget=5.10"

# install the plugin into the local Archi dropins folder (restart Archi afterwards)
install:
    {{gw}} :plugin:installDev

# start a second Archi as another analyst (own settings and model folder, live mode on) to try collaboration on one machine
[windows]
second-archi name="Bob" archi="E:/Archi":
    $s = "$env:APPDATA/Archi-{{name}}/.metadata/.plugins/org.eclipse.core.runtime/.settings"; if (-not (Test-Path "$s/io.github.p3chys.cooperativearchi.prefs")) { New-Item -ItemType Directory -Force $s | Out-Null; $f = "$env:USERPROFILE/Documents/Archi/cooperativeArchi-{{name}}" -replace '\\','/'; Set-Content -Encoding ascii "$s/io.github.p3chys.cooperativearchi.prefs" "eclipse.preferences.version=1`nauthorName={{name}} (test)`nfolder=$f`nlive=true" }
    Start-Process "{{archi}}/Archi.exe" -ArgumentList '--launcher.appendVmargs','-vmargs',"-Dosgi.instance.area=$env:APPDATA/Archi-{{name}}","-Dosgi.configuration.area=$env:APPDATA/Archi-{{name}}/config"

# run the command line client, e.g. just cli sync <workspaceDir>
cli *args:
    {{gw}} -q :core:cliJar
    java -jar core/build/libs/cooperativearchi-cli.jar {{args}}

# everything: tests + plugin + cli, for every supported Archi line
check:
    {{gw}} build :core:cliJar "-ParchiTarget=5.4"
    {{gw}} build :core:cliJar "-ParchiTarget=5.10"
