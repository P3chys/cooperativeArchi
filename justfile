set windows-shell := ["pwsh", "-NoLogo", "-NoProfile", "-Command"]

gw := if os_family() == "windows" { ".\gradlew.bat" } else { "./gradlew" }

# list recipes
default:
    @just --list --unsorted

# core tests (pass -ParchiHome=... to validate merges against another Archi)
test *args:
    {{gw}} :core:test {{args}}

# build the installable .archiplugin into build/dist
plugin:
    {{gw}} :plugin:archiplugin

# install the plugin into the local Archi dropins folder (restart Archi afterwards)
install:
    {{gw}} :plugin:installDev

# run the command line client, e.g. just cli sync <workspaceDir>
cli *args:
    {{gw}} -q :core:cliJar
    java -jar core/build/libs/cooperativearchi-cli.jar {{args}}

# everything: tests + plugin + cli
check:
    {{gw}} build :core:cliJar
