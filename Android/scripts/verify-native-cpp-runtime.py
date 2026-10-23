#!/usr/bin/env python3
"""Pin one APK libc++ runtime and check its exported symbols against consumers.

Uses only Python's standard library. This checks ELF names/versions and packaged
dependencies; it does not replace loading the libraries on an Android device.
"""

import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import zipfile


def read_elf(blob, label):
    if blob[:4] != b"\x7fELF":
        raise ValueError(f"Not an ELF file: {label}")
    is64 = blob[4] == 2
    endian = "<" if blob[5] == 1 else ">"
    header = struct.unpack_from(
        endian + ("16sHHIQQQIHHHHHH" if is64 else "16sHHIIIIIHHHHHH"), blob
    )
    sections = [
        struct.unpack_from(
            endian + ("IIQQQQIIQQ" if is64 else "IIIIIIIIII"),
            blob, header[6] + index * header[11],
        )
        for index in range(header[12])
    ]

    def data(section):
        return blob[section[4]:section[4] + section[5]]

    def string(table, offset):
        end = table.find(b"\0", offset)
        return table[offset:end if end >= 0 else None].decode("utf-8", "replace")

    names = data(sections[header[13]])
    named = {string(names, section[0]): section for section in sections}
    symbols = next(section for section in sections if section[1] == 11)
    strings = data(sections[symbols[6]])
    required_versions = {}
    defined_versions = {}
    if ".gnu.version_r" in named:
        content = data(named[".gnu.version_r"])
        offset = 0
        while offset < len(content):
            _, count, library, auxiliary, following = struct.unpack_from(
                endian + "HHIII", content, offset
            )
            cursor = offset + auxiliary
            for _ in range(count):
                _, flags, index, name, next_auxiliary = struct.unpack_from(
                    endian + "IHHII", content, cursor
                )
                required_versions[index] = {
                    "library": string(strings, library),
                    "version": string(strings, name), "flags": flags,
                }
                if not next_auxiliary:
                    break
                cursor += next_auxiliary
            if not following:
                break
            offset += following
    if ".gnu.version_d" in named:
        content = data(named[".gnu.version_d"])
        offset = 0
        while offset < len(content):
            _, _, index, _, _, auxiliary, following = struct.unpack_from(
                endian + "HHHHIII", content, offset
            )
            name, _ = struct.unpack_from(endian + "II", content, offset + auxiliary)
            defined_versions[index] = string(strings, name)
            if not following:
                break
            offset += following
    version_table = data(named[".gnu.version"]) if ".gnu.version" in named else b""
    exports, undefined = {}, {}
    for index, offset in enumerate(range(symbols[4], symbols[4] + symbols[5], symbols[9])):
        symbol = struct.unpack_from(
            endian + ("IBBHQQ" if is64 else "IIIBBH"), blob, offset
        )
        name, info, visibility, section = (
            (symbol[0], symbol[1], symbol[2], symbol[3]) if is64
            else (symbol[0], symbol[3], symbol[4], symbol[5])
        )
        name = string(strings, name)
        if not name:
            continue
        version = (
            struct.unpack_from(endian + "H", version_table, index * 2)[0] & 0x7FFF
            if version_table else 1
        )
        binding = info >> 4
        if section == 0 and binding != 2:  # Strong imports; weak imports may stay unresolved.
            undefined[name] = required_versions.get(version)
        elif section != 0 and binding in (1, 2, 10) and visibility & 3 in (0, 3):
            exports[name] = defined_versions.get(version)
    needed = []
    for section in sections:
        if section[1] == 6:
            for offset in range(section[4], section[4] + section[5], section[9]):
                tag, value = struct.unpack_from(endian + ("qQ" if is64 else "iI"), blob, offset)
                if tag == 1:
                    needed.append(string(strings, value))
    android_note = {}
    if ".note.android.ident" in named:
        content = data(named[".note.android.ident"])
        offset = 0
        while offset + 12 <= len(content):
            name_size, descriptor_size, _ = struct.unpack_from(endian + "III", content, offset)
            offset += 12
            name = content[offset:offset + name_size].rstrip(b"\0")
            offset += (name_size + 3) // 4 * 4
            descriptor = content[offset:offset + descriptor_size]
            offset += (descriptor_size + 3) // 4 * 4
            if name == b"Android" and len(descriptor) >= 4:
                android_note = {
                    "api": struct.unpack_from(endian + "I", descriptor)[0],
                    "ndk": descriptor[4:68].rstrip(b"\0").decode("utf-8", "replace"),
                    "build": descriptor[68:132].rstrip(b"\0").decode("utf-8", "replace"),
                }
    return {
        "source": label, "sha256": hashlib.sha256(blob).hexdigest(),
        "exports": exports, "undefined": undefined, "needed": needed,
        "androidNote": android_note,
    }


def provider_evidence(arguments):
    document = json.loads(arguments.sources.read_text())
    sources = document["providers"] if isinstance(document, dict) else document
    providers = {}
    source_consumers = {}
    for source in sources:
        if source["abi"] != arguments.abi:
            continue
        if "coordinate" in source:
            group, artifact, version = source["coordinate"].split(":")
            cache = arguments.cache.expanduser()
            cache_roots = [
                cache / "caches/modules-2/files-2.1", cache / "modules-2/files-2.1",
                cache / "files-2.1", cache,
            ]
            candidates = sorted(set(
                file for root in cache_roots
                for file in (root / group / artifact / version).glob(f"*/{artifact}-{version}.aar")
            ))
            matched = []
            for file in candidates:
                with zipfile.ZipFile(file) as archive:
                    if source["aarEntry"] in archive.namelist():
                        content = archive.read(source["aarEntry"])
                        if hashlib.sha256(content).hexdigest() == source["sha256"]:
                            matched.append(file)
            if not matched:
                raise ValueError(
                    f"No cached exact AAR for {source['coordinate']} with pinned "
                    f"{source['aarEntry']} SHA256 {source['sha256']}; searched {cache}"
                )
            label = source["coordinate"]
            with zipfile.ZipFile(matched[0]) as archive:
                providers[label] = read_elf(archive.read(source["aarEntry"]), label)
                prefix = source["aarEntry"].rsplit("/", 1)[0] + "/"
                for entry in sorted(archive.namelist()):
                    if entry.startswith(prefix) and entry.endswith(".so") and Path(entry).name != "libc++_shared.so":
                        consumer_label = label + "/" + Path(entry).name
                        source_consumers[consumer_label] = read_elf(archive.read(entry), consumer_label)
        else:
            # Retain support for a locally generated report of transformed SOs.
            file = Path(source["file"])
            runtime = read_elf(file.read_bytes(), source["source"])
            if runtime["sha256"] != source["sha256"]:
                raise ValueError(f"Source runtime hash changed: {file}")
            providers[source["source"]] = runtime
            for consumer in sorted(file.parent.glob("*.so")):
                if consumer.name != "libc++_shared.so":
                    label = source["source"] + "/" + consumer.name
                    source_consumers[label] = read_elf(consumer.read_bytes(), label)
    if not providers:
        raise ValueError(f"No {arguments.abi} runtime providers in {arguments.sources}")
    return providers, source_consumers


def verify(arguments):
    providers, source_consumers = provider_evidence(arguments)
    exports_union = set().union(*(set(provider["exports"]) for provider in providers.values()))
    packaged = {}
    with zipfile.ZipFile(arguments.apk) as archive:
        native = [entry for entry in archive.infolist() if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        duplicates = [name for name, count in collections.Counter(entry.filename for entry in native).items() if count > 1]
        if duplicates:
            raise ValueError(f"Duplicate APK native entries: {duplicates}")
        abis = sorted({entry.filename.split("/")[1] for entry in native})
        for entry in native:
            if entry.filename.startswith(f"lib/{arguments.abi}/"):
                packaged[Path(entry.filename).name] = read_elf(archive.read(entry), entry.filename)
    selected = packaged.get("libc++_shared.so")
    if selected is None:
        raise ValueError(f"APK does not contain lib/{arguments.abi}/libc++_shared.so")
    errors = []
    if selected["sha256"] != arguments.expected_sha256:
        errors.append(f"APK runtime hash {selected['sha256']} does not match pinned {arguments.expected_sha256}")
    matched = [name for name, provider in providers.items() if provider["sha256"] == selected["sha256"]]
    if not matched:
        errors.append("APK runtime does not match any verified provider from the source report")
    if arguments.only_abi and abis != [arguments.abi]:
        errors.append(f"APK contains ABIs {abis}; expected only {arguments.abi}")
    consumers = dict(source_consumers)
    consumers.update({"apk/" + name: library for name, library in packaged.items() if name != "libc++_shared.so"})
    all_packaged_exports = set().union(*(set(library["exports"]) for library in packaged.values()))
    requirements = []
    demand = set()
    for label, consumer in sorted(consumers.items()):
        required = set(consumer["undefined"]) & exports_union
        demand.update(required)
        missing = sorted(required - set(selected["exports"]))
        mismatched_versions = {
            name: version for name, version in consumer["undefined"].items()
            if name in required and version and version["library"] == "libc++_shared.so"
            and selected["exports"].get(name) != version["version"]
        }
        # These namespace names belong to libc++; other undefined names may come
        # from Android system libraries or another packaged library.
        unresolved_standard = sorted(
            name for name in consumer["undefined"]
            if "St6__ndk1" in name and name not in all_packaged_exports
        ) if label.startswith("apk/") else []
        if missing or mismatched_versions or unresolved_standard:
            errors.append(f"Unresolved C++ runtime imports in {label}")
        requirements.append({
            "consumer": label, "requiredRuntimeSymbolCount": len(required),
            "requiredRuntimeSymbols": sorted(required),
            "missingRuntimeSymbols": missing, "mismatchedRuntimeVersions": mismatched_versions,
            "unresolvedStandardLibrarySymbols": unresolved_standard,
            "needed": consumer["needed"],
        })
    platform = {
        "libandroid.so", "libc.so", "libdl.so", "libEGL.so", "libGLESv1_CM.so",
        "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so", "liblog.so", "libm.so",
        "libOpenMAXAL.so", "libOpenSLES.so", "libstdc++.so", "libz.so", "libmediandk.so",
        "libcamera2ndk.so", "libvulkan.so", "libnativewindow.so", "libaaudio.so",
        "libneuralnetworks.so", "libsync.so",
    }
    unpackaged = sorted(set().union(*(set(library["needed"]) for library in packaged.values())) - set(packaged) - platform)
    return {
        "status": "pass" if not errors else "fail", "errors": errors,
        "apk": str(arguments.apk), "abi": arguments.abi, "packagedAbis": abis,
        "selectedRuntime": {
            "sha256": selected["sha256"], "matchedSources": matched,
            "exports": len(selected["exports"]), "androidNote": selected["androidNote"],
        },
        "runtimeExportUnionCount": len(exports_union), "requiredRuntimeSymbolCount": len(demand),
        "sourceConsumerCount": len(source_consumers), "packagedLibraryCount": len(packaged),
        "providers": {name: {
            "sha256": provider["sha256"], "exports": len(provider["exports"]),
            "androidNote": provider["androidNote"],
            "missingRequiredSymbols": sorted(demand - set(provider["exports"])),
        } for name, provider in providers.items()},
        "consumers": requirements,
        "unpackagedNonPlatformNeededLibraries": unpackaged,
        "scope": "ELF strong symbol names/versions and pinned APK runtime only; device loading, C++ object-layout ABI and 16 KB compatibility require separate tests",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument(
        "--sources", type=Path,
        default=Path(__file__).resolve().parents[1] / "gradle/native-cpp-providers.json",
        help="Pinned GAV/AAR-entry manifest (default: ../gradle/native-cpp-providers.json), or a local transformed-SO report",
    )
    parser.add_argument(
        "--cache", type=Path,
        default=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))),
        help="Gradle user home or modules-2/files-2.1 cache root; no network access or downloads",
    )
    parser.add_argument("--expected-sha256", required=True, help="Pinned selected libc++_shared.so hash")
    parser.add_argument("--abi", default="arm64-v8a")
    parser.add_argument("--only-abi", action="store_true")
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    try:
        result = verify(arguments)
    except (OSError, ValueError, KeyError, StopIteration, struct.error, zipfile.BadZipFile) as error:
        print(f"Native runtime verification error: {error}", file=sys.stderr)
        return 2
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: value for key, value in result.items() if key not in ("consumers", "providers")}, indent=2))
    return 0 if result["status"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
