{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:

let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "37.0" ];
    buildToolsVersions = [ "36.0.0" ];
    includeNDK = true;
    ndkVersions = [ "29.0.14206865" ];
    cmakeVersions = [ "3.22.1" ];
    includeCmake = true;
  };
  sdk = android.androidsdk;
in
pkgs.mkShell {
  # Nix OpenJDK links system libjpeg, which can interpose on Robolectric's
  # bundled JPEG symbols and crash native graphics. Temurin bundles its JPEG.
  packages = [ pkgs.temurin-bin-21 pkgs.python3 pkgs.git sdk ];
  JAVA_HOME = "${pkgs.temurin-bin-21}";
  # Robolectric/Conscrypt extract native libraries outside the Nix store.
  LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.stdenv.cc.cc.lib ];
  ANDROID_HOME = "${sdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
  ANDROID_NDK_HOME = "${sdk}/libexec/android-sdk/ndk/29.0.14206865";
}
