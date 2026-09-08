# Building the 3DS plugin

Working as of devkitARM r68 / GCC 16.1 on macOS arm64. Three things bite, all of them because the
3DS plugin ecosystem is older than the current toolchain.

## Use CTRComposer, not CTRPluginFramework

CTRPluginFramework ships a prebuilt `libCTRPluginFramework.a` built years ago on Windows against an
old newlib. Linking it with r68 fails on `undefined reference to __syscalls`. The maintained forks
are stale too.

`plugin/composer` (CTRComposer) is a raw `.3gx` engine targeting devkitARM + libctru directly, with
no framework in between. It builds clean against r68 — which is also all this plugin needs, since we
want a service hook and sockets, not a cheat menu.

## 3gxtool has to be built by hand

Its Makefile is MinGW-only (`.exe`, `crt0.o`) and its bundled yaml-cpp headers are 0.5-era, which do
not match Homebrew's 0.9 library — mixing them links against symbols that no longer exist. Build it
against Homebrew's yaml-cpp with a shim for the old include path:

    brew install yaml-cpp
    cd plugin/3gxtool
    mkdir -p /tmp/yshim && printf '#pragma once\n#include <yaml-cpp/yaml.h>\n' > /tmp/yshim/yaml.h
    clang++ -std=c++17 -O2 -w -o ../bin/3gxtool sources/main.cpp \
        -Iincludes -I/tmp/yshim -I"$(brew --prefix yaml-cpp)/include" \
        -L"$(brew --prefix yaml-cpp)/lib" -lyaml-cpp

The binary is checked in at `plugin/bin/3gxtool` so this does not have to be repeated.

## Building

    export DEVKITPRO=/opt/devkitpro DEVKITARM=/opt/devkitpro/devkitARM
    export PATH=$PWD/plugin/bin:$DEVKITARM/bin:$DEVKITPRO/tools/bin:$PATH
    cd plugin/composer && make

Produces a `.3gx` for Luma3DS's plugin loader.
