{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:

let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "37" ];
    buildToolsVersions = [ "36.0.0" ];
    includeNDK = true;
    ndkVersions = [ "29.0.14206865" ];
    cmakeVersions = [ "3.22.1" ];
    includeCmake = true;
  };
  sdk = android.androidsdk;
in
pkgs.mkShell {
  packages = [ pkgs.jdk21 pkgs.python3 pkgs.git sdk ];
  JAVA_HOME = pkgs.jdk21.home;
  ANDROID_HOME = "${sdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
  ANDROID_NDK_HOME = "${sdk}/libexec/android-sdk/ndk/29.0.14206865";
}
