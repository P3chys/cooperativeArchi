set windows-shell := ["pwsh", "-NoLogo", "-NoProfile", "-Command"]

gw := if os_family() == "windows" { ".\gradlew.bat" } else { "./gradlew" }

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

# run the command line client, e.g. just cli sync <workspaceDir>
cli *args:
    {{gw}} -q :core:cliJar
    java -jar core/build/libs/cooperativearchi-cli.jar {{args}}

# everything: tests + plugin + cli, for every supported Archi line
check:
    {{gw}} build :core:cliJar "-ParchiTarget=5.4"
    {{gw}} build :core:cliJar "-ParchiTarget=5.10"
