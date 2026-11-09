{ pkgs ? import <nixpkgs> { config = { allowUnfree = true; android_sdk.accept_license = true; }; } }:
let
  buildTools = "36.0.0";
  sdk = (pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "36" ];
    buildToolsVersions = [ buildTools ];
    includeEmulator = false;
  }).androidsdk;
  home = "${sdk}/libexec/android-sdk";
in
pkgs.mkShell {
  packages = [ pkgs.jdk17 pkgs.android-tools ];
  ANDROID_HOME = home;
  # Gradle's own aapt2 download isn't patched for NixOS; use the SDK's.
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${home}/build-tools/${buildTools}/aapt2";
}
