# Tailscale Android Client

https://tailscale.com

Private WireGuard® networks made easy

## Overview

This repository contains the open source Tailscale Android client.

## Using

#### Tailscale Packages

The latest stable release APK can be obtained from the [Tailscale Packages Stable Track](https://pkgs.tailscale.com/stable/#android).

Unstable releases can be obtained from the [Tailscale Packages Unstable Track](https://pkgs.tailscale.com/unstable/#android).

These APKs include all supported platforms and architectures.  For installing compact APKs, Android TV, or if you want automatic updates, visit the [Google Play Store](https://play.google.com/store/apps/details?id=com.tailscale.ipn).

#### Google Playstore

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=com.tailscale.ipn)

Help us test new features and bug-fixes before they ship to all users! A [beta testing track](https://play.google.com/apps/testing/com.tailscale.ipn) is available on the Play Store. 

#### Amazon Appstore

The app can be downloaded from the [Amazon Appstore](https://www.amazon.com/dp/B0D38TRB3N) for Amazon Fire tablets and Fire TV devices.

#### F-Droid

The [F-Droid](https://f-droid.org/packages/com.tailscale.ipn/) project builds the source code in this repository and maintains independently-built APKs. Note that F-Droid builds are not released, updated, or verified by the Tailscale team.

## Preparing a build environment

There are several options for setting up a build environment. The Android Studio
path is the most useful path for longer term development.

In all cases you will need:

- Go runtime
- Android SDK
- Android SDK components (`make androidsdk` will install them)

### Android Studio

1. Install a Go runtime (https://go.dev/dl/).
2. Install Android Studio (https://developer.android.com/studio).
3. Start Android Studio, from the Welcome screen select "More Actions" and "SDK Manager".
4. In the SDK manager, select the "SDK Tools" tab and install the "Android SDK Command-line Tools (latest)".
3. Run `make androidsdk` to install the necessary SDK components.

If you would prefer to avoid Android Studio, you can also install an Android
SDK. The makefile detects common paths, so `sudo apt install android-sdk` is
sufficient on Debian / Ubuntu systems. To use an Android SDK installed in a
non-standard location, set the `ANDROID_SDK_ROOT` environment variable to the
path to the SDK.

If you installed Android Studio the tools may not be in your path. To get the
correct tool path, run `make androidpath` and export the provided path in your
shell.

#### Code Formatting

The ktmft plugin on the default setting should be used to autoformat all Java, Kotlin
and XML files in Android Studio.  Enable "Format on Save".

### Docker

If you wish to avoid installing software on your host system, a Docker based development strategy is available, you can build and start a shell with:

```sh
make docker-shell
```

Several other makefile recipes are available for setting up the proper build environment and running builds.

Note that the docker makefile recipes s will preserve the image and remove container on completion.
If changes are made to the build environment or toolchain, cached docker images may need to be rebuilt.
The docker build image name is parameterized in the makefile and changing it provides a simple means to do this.

### Nix

If you have Nix 2.4 or later installed, a Nix development environment can
be set up with:

```sh
alias nix='nix --extra-experimental-features "nix-command flakes"'
nix develop
```

The flake provides host tools such as Java, `make`, `curl`, and `git`, and
points the build at a repo-local Android SDK in `./android-sdk`. The SDK
directory is ignored by Git and is reused across builds.

On first use, install the Android SDK components:

```sh
make androidsdk
```

Then build normally:

```sh
make tailscale-debug
```

The debug APK is written to `./tailscale-debug.apk`.

For one-shot commands without entering an interactive shell:

```sh
nix develop --command make androidsdk
nix develop --command make tailscale-debug
```

For faster Kotlin-only iteration while avoiding the `gomobile bind` step:

```sh
nix develop --command bash -lc 'cd android && ./gradlew ktfmtCheck compileDebugKotlin'
```

## Building

```sh
make apk
make install
```

## Building a release

Use `make tag_release` to stamp the Play Store version code, update the version
name, and tag the current commit. The version code is derived from wall-clock
time (minutes since the Unix epoch) at release time and committed into
`android/build.gradle`, so it increases monotonically across all builds and
branches while staying fixed for any given commit.

We only guarantee to support the latest Go release and any Go beta or
release candidate builds (currently Go 1.14) in module mode. It might
work in earlier Go versions or in GOPATH mode, but we're making no
effort to keep those working.

## Developing on a Fire Stick TV

On the Fire Stick:

* Settings > My Fire TV > Developer Options > ADB Debugging > ON

Then some useful commands:
```
adb connect 10.2.200.213:5555
adb install -r tailscale-fdroid.apk
adb shell am start -n com.tailscale.ipn/com.tailscale.ipn.MainActivity
adb shell pm uninstall com.tailscale.ipn
```

## Changelog

### vehicle-298988300（1.105.129）

座舱保活，装在 `com.tailscale.ipn` 进程里：

- `VehicleKeepAliveService` 常驻前台。开机、华为上电、亮屏、解锁和 `VEHICLE_WATCHDOG` 都进这一条。
- 60 秒精确心跳 `VEHICLE_HEARTBEAT`（`setExactAndAllowWhileIdle`，需要 `SCHEDULE_EXACT_ALARM`）。10 分钟看门狗和 5 秒启动探测用 `PendingIntent.getForegroundService`。
- 每 5 秒按 Tailscale 自己的状态做健康检查。`Running` / `Starting` 不重复拨号；`WantRunning` 为假、需要登录或需要机器授权时不拨。
- 持久 Job `3147087`。健康检查发现 Job 或闹钟缺失会补上。
- `content://com.tailscale.ipn.keepalive` 的 `startProc` 可从外部拉起进程。
- `IPNService` 在非主动停止时，销毁后 5 秒、任务划掉后 2 秒拉回保活服务。
- `CONNECT_VPN` 在已经允许 VPN 时，于广播里直接 `startVPN()`。

语言：

- 新增 `values-zh-rCN` 简体中文资源。
- 设置页可选跟随系统、简体中文或 English。

对应调试包是 arm64，使用本机 Android 调试证书，不是 Tailscale 官方签名。同版本官方包要先卸载才能换上这份包。

## Bugs

Please file any issues about this code or the hosted service on
[the tailscale issue tracker](https://github.com/tailscale/tailscale/issues).

## Contributing

`under_construction.gif`

PRs welcome, but we are still working out our contribution process and
tooling.

We require [Developer Certificate of
Origin](https://en.wikipedia.org/wiki/Developer_Certificate_of_Origin)
`Signed-off-by` lines in commits.

## About Us

We are [Tailscale](https://tailscale.com). See
https://tailscale.com/company for more about us and what we're
building.

WireGuard is a registered trademark of Jason A. Donenfeld.
