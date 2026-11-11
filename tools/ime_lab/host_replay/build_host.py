"""Build the production Rime/T9 JNI stack on Linux, without Android or an APK.

All configure-time production patches run only in a fingerprinted source copy.
Dependencies are downloaded into the requested private cache, never installed
globally. Build stdout ends with one JSON object; progress/logs use stderr.
"""
from __future__ import annotations

import argparse
import ctypes
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import uuid
import zipfile

VERSIONS = {"cmake": "3.31.6", "ninja": "1.11.1.4", "onnxruntime": "1.31.1"}
JNI_TAG = "jdk-11.0.28%2B6"
HERE = Path(__file__).resolve().parent
CODE_SUFFIXES = {".h", ".hpp", ".c", ".cc", ".cpp", ".inc", ".cmake", ".patch", ".in", ".txt"}


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def save(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def run(arguments, **kwargs):
    print("HOST " + " ".join(map(str, arguments)), file=sys.stderr, flush=True)
    return subprocess.run(list(map(str, arguments)), check=True, **kwargs)


def download(url, path, expected=None):
    path = Path(path)
    if path.is_file() and (expected is None or sha(path) == expected):
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".download-" + uuid.uuid4().hex)
    try:
        print("DOWNLOAD " + url, file=sys.stderr, flush=True)
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        if expected is not None and sha(temporary) != expected:
            raise ValueError("Downloaded hash mismatch: " + url)
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def wheel(package, cache):
    version = VERSIONS[package]
    metadata_path = cache / f"{package}-{version}.pypi.json"
    download(f"https://pypi.org/pypi/{package}/{version}/json", metadata_path)
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    candidates = [entry for entry in metadata["urls"] if entry["filename"].endswith(".whl")
                  and "manylinux" in entry["filename"] and "x86_64" in entry["filename"]
                  and (package != "onnxruntime" or "cp311-cp311-" in entry["filename"])]
    if not candidates:
        raise ValueError(f"No supported Linux x64 wheel for {package}")
    entry = sorted(candidates, key=lambda item: item["filename"])[-1]
    archive = cache / entry["filename"]
    download(entry["url"], archive, entry["digests"]["sha256"])
    destination = cache / f"{package}-{version}"
    marker = destination / ".complete"
    if not marker.exists():
        destination.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as zipped:
            for info in zipped.infolist():
                name = info.filename
                if package == "onnxruntime" and not ("/capi/libonnxruntime" in name and ".so" in name):
                    continue
                target = (destination / name).resolve()
                if not target.is_relative_to(destination.resolve()):
                    raise ValueError("Unsafe wheel member: " + name)
                if info.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with zipped.open(info) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                permissions = info.external_attr >> 16
                if permissions:
                    target.chmod(permissions & 0o777)
        marker.write_text(entry["digests"]["sha256"], encoding="ascii")
    return destination, {"version": version, "source": entry["url"], "wheel_sha256": sha(archive)}


def dependencies(cache, ort_api):
    cache.mkdir(parents=True, exist_ok=True)
    cmake, cmake_identity = wheel("cmake", cache)
    ninja, ninja_identity = wheel("ninja", cache)
    ort, ort_identity = wheel("onnxruntime", cache)
    cmake_binary = next(cmake.glob("cmake/data/bin/cmake"))
    ninja_binary = next(ninja.glob("*.data/scripts/ninja"))
    library = next(ort.glob("onnxruntime/capi/libonnxruntime.so.*"))
    library.chmod(0o755)
    link = library.parent / "libonnxruntime.so"
    if not link.exists():
        link.symlink_to(library.name)
    soname = library.parent / "libonnxruntime.so.1"
    if not soname.exists():
        soname.symlink_to(library.name)
    class ApiBase(ctypes.Structure):
        _fields_ = [("get_api", ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_uint32)),
                    ("get_version", ctypes.CFUNCTYPE(ctypes.c_char_p))]
    native = ctypes.CDLL(str(library))
    native.OrtGetApiBase.restype = ctypes.POINTER(ApiBase)
    api = native.OrtGetApiBase().contents
    if not api.get_api(ort_api):
        raise ValueError(f"Linux ONNX Runtime does not support production header API {ort_api}")
    ort_identity.update(library_sha256=sha(library), runtime_version=api.get_version().decode(), api=ort_api)
    include = cache / "jni-11.0.28"
    identity = {}
    for leaf, source in (("jni.h", "share/native/include/jni.h"), ("linux/jni_md.h", "unix/native/include/jni_md.h")):
        url = f"https://raw.githubusercontent.com/openjdk/jdk11u/{JNI_TAG}/src/java.base/{source}"
        download(url, include / leaf)
        identity[leaf] = {"source": url, "sha256": sha(include / leaf)}
    java = shutil.which("java")
    if not java:
        raise ValueError("Host Java 11+ runtime is required")
    run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-version"], stdout=sys.stderr)
    javac = cache / "javac"
    javac.write_text('#!/bin/sh\nexec "' + java + '" -m jdk.compiler/com.sun.tools.javac.Main "$@"\n', encoding="utf-8")
    javac.chmod(0o755)
    return {"cmake": cmake_binary, "ninja": ninja_binary, "onnx": library, "jni": include,
            "java": Path(java), "javac": javac}, {"cmake": cmake_identity, "ninja": ninja_identity,
                                                    "onnxruntime": ort_identity, "jni_headers": identity}


def snapshot(repo, cache):
    source = repo / "app/src/main/jni"
    staging = cache / ("snapshot-" + uuid.uuid4().hex)
    staging.mkdir(parents=True)
    # Native Windows tar avoids tens of thousands of slow WSL/DrvFS stat calls.
    windows_tar = Path("/mnt/c/Windows/System32/tar.exe")
    if windows_tar.is_file() and re.match(r"/mnt/[a-z]/", str(source)):
        windows_source = subprocess.check_output(["wslpath", "-w", str(source)], text=True).strip()
        arguments = [str(windows_tar), "-cf", "-", "--exclude=.git", "--exclude=./onnxruntime/lib",
                     "--exclude=./librime/plugins", "-C", windows_source, "."]
    else:
        arguments = ["tar", "-cf", "-", "--exclude=.git", "--exclude=./onnxruntime/lib",
                     "--exclude=./librime/plugins", "-C", str(source), "."]
    print("SNAPSHOT " + str(source), file=sys.stderr, flush=True)
    process = subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=sys.stderr)
    files = {}
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            for member in archive:
                target = (staging / member.name).resolve()
                if not target.is_relative_to(staging.resolve()):
                    raise ValueError("Snapshot member escapes its directory: " + member.name)
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    digest = hashlib.sha256()
                    with archive.extractfile(member) as input_file, target.open("wb") as output:
                        if target.suffix in CODE_SUFFIXES:
                            content = input_file.read()
                            digest.update(content)
                            output.write(content.replace(b"\r\n", b"\n"))
                        else:
                            for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
                                digest.update(chunk)
                                output.write(chunk)
                    target.chmod(member.mode & 0o777)
                    files[str(target.relative_to(staging))] = digest.hexdigest()
                elif member.issym() or member.islnk():
                    raise ValueError("Source snapshot requires regular files, not symlinks: " + member.name)
        if process.wait():
            raise RuntimeError("Source snapshot failed")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
    # The plugin directory contains generated copies; retain only its dispatcher.
    plugin_cmake = source / "librime/plugins/CMakeLists.txt"
    copied = staging / "librime/plugins/CMakeLists.txt"
    copied.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(plugin_cmake, copied)
    files["librime/plugins/CMakeLists.txt"] = sha(copied)
    identity = hashlib.sha256(json.dumps(files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    destination = cache / "sources" / identity
    destination.parent.mkdir(exist_ok=True)
    if destination.exists():
        # This directory was created by this invocation and is inside the cache.
        if staging.parent != cache or not staging.name.startswith("snapshot-"):
            raise ValueError("Refusing to remove an unowned temporary snapshot")
        shutil.rmtree(staging)
    else:
        staging.rename(destination)
    return destination, identity, files


def build(args):
    if sys.platform != "linux" or os.uname().machine != "x86_64":
        raise ValueError("This backend requires Linux x86_64 (including WSL)")
    repo, cache = args.repo.resolve(), args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    source, identity, files = snapshot(repo, cache)
    header = source / "onnxruntime/include/onnxruntime_c_api.h"
    match = re.search(r"^#define ORT_API_VERSION\s+(\d+)\b", header.read_text(encoding="utf-8"), re.MULTILINE)
    if not match:
        raise ValueError("Cannot determine production ONNX Runtime API version")
    tools, dependency_identity = dependencies(cache / "dependencies", int(match.group(1)))
    production = (source / "CMakeLists.txt").read_text(encoding="utf-8")
    # A reused configured snapshot already contains the host prefix; use its original.
    original = source / "CMakeLists.production.txt"
    if original.exists():
        production = original.read_text(encoding="utf-8")
    else:
        original.write_text(production, encoding="utf-8")
    anchor = "include(Rime)"
    if production.count(anchor) != 1:
        raise ValueError("Production CMake layout changed; review host adaptation")
    prefix = production[:production.index(anchor)] + '''list(PREPEND CMAKE_MODULE_PATH "${HOST_ADAPTER_DIR}")
# Boost modular targets in this checkout omit some transitive header dependencies.
file(GLOB HOST_BOOST_INCLUDES LIST_DIRECTORIES true "${CMAKE_SOURCE_DIR}/boost/libs/*/include")
include_directories(${HOST_BOOST_INCLUDES})
''' + anchor
    tail = (HERE / "host_tail.cmake").read_text(encoding="utf-8")
    (source / "CMakeLists.txt").write_text(prefix + "\n" + tail, encoding="utf-8")
    adapter_hashes = {name: sha(HERE / name) for name in ("build_host.py", "host_tail.cmake", "android/log.h", "FindBoost.cmake",
                                                         "host_replay_jni.cc")}
    build_id = hashlib.sha256(json.dumps({"source": identity, "adapters": adapter_hashes,
                                        "dependencies": dependency_identity}, sort_keys=True).encode()).hexdigest()[:20]
    directory = cache / "builds" / build_id
    directory.mkdir(parents=True, exist_ok=True)
    adapters = directory / "host-adapters"
    for name in adapter_hashes:
        target = adapters / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(HERE / name, target)
        if sha(target) != adapter_hashes[name]:
            raise ValueError("Host adapter changed during snapshot: " + name)
    manifest_path = directory / "build-manifest.json"
    save(directory / "source-files.json", files)
    configure = [tools["cmake"], "-S", source, "-B", directory, "-G", "Ninja",
                 "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                 "-DCMAKE_MAKE_PROGRAM=" + str(tools["ninja"]), "-DHOST_ADAPTER_DIR=" + str(adapters),
                 "-DHOST_JNI_INCLUDE=" + str(tools["jni"]), "-DHOST_ORT_DIR=" + str(tools["onnx"].parent),
                 "-DHOST_ORT_LIBRARY=" + str(tools["onnx"]), "-DBUILD_TESTING=OFF", "-DBUILD_TEST=OFF",
                 # Host-only per-key work counters; Android builds leave this OFF.
                 "-DCYIME_T9_WORK_COUNTERS=ON"]
    compile_args = [tools["cmake"], "--build", directory, "--target", "rime_jni", "--parallel", "2"]
    manifest = {"format_version": 1, "status": "building", "backend": "linux-x86_64-production-jni",
                "source_repo": str(repo), "source_root": str(source), "source_sha256": identity,
                "source_files": "source-files.json", "source_files_sha256": sha(directory / "source-files.json"),
                "source_file_count": len(files), "adapter_hashes": adapter_hashes,
                "source_transforms": ["CRLF normalized to LF in C/C++/CMake/text inputs before production git-apply checks",
                                      "Android top-level build replaced after include(Rime) with host JNI linkage",
                                      "FindBoost uses the production vendored Boost 1.89 target",
                                      "CYIME_T9_WORK_COUNTERS=ON: per-key work counters and host_replay_jni.cc entry points"],
                "dependencies": dependency_identity, "configure": list(map(str, configure)),
                "compile": list(map(str, compile_args)), "started_utc": datetime.now(timezone.utc).isoformat()}
    save(manifest_path, manifest)
    try:
        with (directory / "build.log").open("w", encoding="utf-8") as log:
            run(configure, stdout=log, stderr=subprocess.STDOUT)
            run(compile_args, stdout=log, stderr=subprocess.STDOUT)
        library = directory / "lib/librime_jni.so"
        # Keep all runtime dependencies discoverable with one library directory.
        ort_link = library.parent / tools["onnx"].name
        if not ort_link.exists():
            ort_link.symlink_to(tools["onnx"])
        for name in ("libonnxruntime.so", "libonnxruntime.so.1"):
            target = library.parent / name
            if not target.exists():
                target.symlink_to(tools["onnx"])
        manifest.update(status="complete", library=str(library), library_sha256=sha(library),
                        library_hashes={p.name: sha(p) for p in sorted(library.parent.glob("*.so*"))},
                        finished_utc=datetime.now(timezone.utc).isoformat())
        save(manifest_path, manifest)
        return {"library_dir": str(library.parent), "library": str(library), "manifest": str(manifest_path),
                "java": str(tools["java"]), "javac": str(tools["javac"])}
    except Exception as error:
        manifest.update(status="failed", error=str(error), finished_utc=datetime.now(timezone.utc).isoformat())
        save(manifest_path, manifest)
        print("Build failure log: " + str(directory / "build.log"), file=sys.stderr)
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(build(args), sort_keys=True), flush=True)
        return 0
    except Exception as error:
        print("HOST BUILD FAILED: " + str(error), file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
