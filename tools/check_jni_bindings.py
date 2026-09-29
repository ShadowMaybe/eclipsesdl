#!/usr/bin/env python3
"""check_jni_bindings — prove the Android Java side and the native side agree.

SDL binds Java and C together with strings, and strings are invisible to the
compiler: the native tables name a method and a JNI signature, GetStaticMethodID
names more of them, and every one of those lookups fails only when the app is
already running on somebody's device, with a warning nobody reads. Renaming a
class or a parameter changes nothing on the build machine and breaks everything
else.

So this reads both sides and compares them:

  1. every JNINativeMethod table entry exists in Java, with the same signature;
  2. every method native code looks up by name exists, with the same signature;
  3. every method Java declares `native` is actually registered by a table;
  4. the class names, packages and the SDL_JAVA_PREFIX macro all agree.

Anything it cannot parse it reports rather than skips: a silent pass would be
worse than a failure, because it would be believed.

Usage:  tools/check_jni_bindings.py [--quiet]
Exit:   0 when both sides match, 1 otherwise.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NATIVE_SOURCES = [
    ROOT / "src" / "core" / "android" / "SDL_android.c",
    ROOT / "src" / "hidapi" / "android" / "hid.cpp",
]
JAVA_ROOT = ROOT / "jni_bindings" / "src" / "main" / "java"

# Class variables held by native code, and the Java class each one points at.
#
# SDL_android.c derives these from the jclass passed to each nativeSetupJNI,
# which the script cross-checks below; hid.cpp takes its class from the object
# whose natives it registered, which has exactly one candidate. The mapping is
# written out here because reading it off C expressions reliably costs more
# subtlety than it is worth, but every line of it is verified, not trusted.
CLASS_VARS = {
    "mActivityClass": "me.shadow.eclipselauncher.sdl.EclipseSDL",
    "mAudioManagerClass": "me.shadow.eclipselauncher.sdl.EclipseAudioManager",
    "mControllerManagerClass": "me.shadow.eclipselauncher.sdl.EclipseControllerManager",
    "g_HIDDeviceManagerCallbackClass": "me.shadow.eclipselauncher.sdl.EclipseHIDDeviceManager",
}

# The three above are only ever held as a Class: native creates no instance of
# them and passes only Class objects to CallStatic*Method. So an instance-method
# lookup on one of them resolves a method the object being called later actually
# has — there is no such object, so it cannot be right. Upstream SDL got away
# with it because its SDLActivity was also an Activity and the Context returned
# by getContext() was one of those. Ours is not, and this rule is here because
# the very first version of the bindings repeated that assumption.
# g_HIDDeviceManagerCallbackClass is deliberately absent: hid.cpp keeps a real
# instance (g_HIDDeviceManagerCallbackHandler) and calls instance methods on it.
STATIC_ONLY_VARS = {
    "mActivityClass",
    "mAudioManagerClass",
    "mControllerManagerClass",
}

PRIMITIVES = {
    "void": "V",
    "boolean": "Z",
    "byte": "B",
    "char": "C",
    "short": "S",
    "int": "I",
    "long": "J",
    "float": "F",
    "double": "D",
}

problems: list[str] = []


def fail(message: str) -> None:
    problems.append(message)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"missing source file: {path.relative_to(ROOT)}")
        return ""
    return path.read_text(encoding="utf-8")


def strip_comments(text: str) -> str:
    """Remove comments, keeping string literals intact (signatures live in them)."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


# --------------------------------------------------------------------------
# 1. Package and class-name macros
# --------------------------------------------------------------------------

def parse_naming() -> tuple[str, dict[str, str]]:
    """Return the JNI prefix (package as underscores) and macro -> class name."""
    src = strip_comments(read(ROOT / "src" / "core" / "android" / "SDL_android.c"))

    m = re.search(r"#\s*define\s+SDL_JAVA_PREFIX\s+(\w+)", src)
    if not m:
        fail("SDL_JAVA_PREFIX is not defined in SDL_android.c")
        prefix = ""
    else:
        prefix = m.group(1)

    macros: dict[str, str] = {}
    for m in re.finditer(
        r"#\s*define\s+(SDL_JAVA\w*)\(function\)\s+CONCAT1\(SDL_JAVA_PREFIX,\s*(\w+),", src
    ):
        macros[m.group(1)] = m.group(2)

    if not macros:
        fail("no SDL_JAVA_* interface macros found in SDL_android.c")

    # The HID macros live in hid.cpp with a different concatenation spelling.
    hid = strip_comments(read(ROOT / "src" / "hidapi" / "android" / "hid.cpp"))
    m = re.search(
        r"#\s*define\s+(HID_DEVICE_MANAGER_JAVA_INTERFACE)\s*\(function\)\s+"
        r"CONCAT1\(SDL_JAVA_PREFIX,\s*(\w+),",
        hid,
    )
    if m:
        macros[m.group(1)] = m.group(2)
    else:
        fail("HID_DEVICE_MANAGER_JAVA_INTERFACE not found in hid.cpp")

    m = re.search(r"#\s*define\s+SDL_JAVA_PREFIX\s+(\w+)", hid)
    if not m:
        fail("SDL_JAVA_PREFIX is not defined in hid.cpp")
    elif prefix and m.group(1) != prefix:
        fail(
            "SDL_JAVA_PREFIX differs between files: "
            f"SDL_android.c has '{prefix}', hid.cpp has '{m.group(1)}'"
        )

    return prefix, macros


def prefix_to_package(prefix: str) -> str:
    """The Java package SDL_JAVA_PREFIX names, in dotted form."""
    return prefix.replace("_", ".")


# --------------------------------------------------------------------------
# 2. Native method tables
# --------------------------------------------------------------------------

TABLE_ENTRY = re.compile(r'\{\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*([^{}]*?)\s*\}')


def parse_tables(src: str) -> dict[str, list[tuple[str, str, str]]]:
    """Map each `X_tab` identifier to its (name, signature, C function) entries.

    The C function matters: its second parameter says whether Java has to
    declare the method static (jclass) or as an instance method (jobject), and
    getting that wrong still compiles — it just fails at RegisterNatives.
    """
    tables: dict[str, list[tuple[str, str, str]]] = {}
    for m in re.finditer(r"JNINativeMethod\s+(\w+)\s*(?:\[[^\]]*\])?\s*=\s*\{(.*?)\};", src, re.S):
        table, body = m.group(1), m.group(2)
        entries = TABLE_ENTRY.findall(body)
        if not entries:
            fail(f"native table {table} has no parseable entries")
        tables[table] = entries
    return tables


def parse_native_kinds(sources: dict[Path, str]) -> dict[tuple[str, str], str]:
    """Map (interface macro, function) to 'static' or 'instance'.

    Read off the actual C declarations rather than assumed, because the two
    halves of the binding really do differ: SDL's methods take a jclass, hidapi's
    take the jobject it wants to call back into.
    """
    kinds: dict[tuple[str, str], str] = {}
    for src in sources.values():
        if not src:
            continue
        text = strip_comments(src)
        for m in re.finditer(
            r"JNICALL\s+(\w+)\s*\(\s*(\w+)\s*\)\s*"
            r"\(\s*JNIEnv\s*\*\s*\w+\s*,\s*(jclass|jobject)\b",
            text,
        ):
            kinds[(m.group(1), m.group(2))] = "static" if m.group(3) == "jclass" else "instance"
    return kinds


def parse_registrations(src: str) -> dict[str, str]:
    """Map each table identifier to the dotted class name registered for it."""
    return {
        m.group(2): m.group(1).replace("/", ".")
        for m in re.finditer(
            r'register_methods\s*\(\s*env\s*,\s*"([^"]+)"\s*,\s*(\w+)\s*,', src
        )
    }


def check_registered_natives(
    tables, registrations, package, macros, kinds
) -> dict[str, list[tuple[str, str, str]]]:
    """Verify table/class/macro consistency; return class FQN -> (name, sig, kind)."""
    by_class: dict[str, list[tuple[str, str, str]]] = {}

    for table, entries in sorted(tables.items()):
        if table not in registrations:
            fail(f"native table {table} is never passed to register_methods()")
            continue
        fqn = registrations[table]

        resolved: list[tuple[str, str, str]] = []
        for name, sig, expr in entries:
            macro_fn = re.search(r"(\w+)\s*\(\s*(\w+)\s*\)", expr)
            if not macro_fn:
                fail(f"table {table}: cannot read the C function for '{name}' out of '{expr}'")
                continue
            kind = kinds.get((macro_fn.group(1), macro_fn.group(2)))
            if kind is None:
                fail(
                    f"table {table}: no C declaration matches {expr}, so the checker cannot "
                    f"tell whether Java must declare '{name}' static or as an instance method"
                )
                kind = "static"
            resolved.append((name, sig, kind))
        by_class.setdefault(fqn, []).extend(resolved)

    # Every registered class must be in our package...
    for fqn in sorted(by_class):
        if not fqn.startswith(package + "."):
            fail(f"native code registers {fqn}, which is outside package {package}")

        # ...and must be named by one of the interface macros, otherwise the
        # symbols in its table are built from a class that is not the class
        # being registered, and RegisterNatives binds them to the wrong object.
        simple = fqn.rsplit(".", 1)[-1]
        if simple not in set(macros.values()):
            fail(
                f"{fqn} is registered, but no SDL_JAVA interface macro names a class "
                f"called {simple} (macros name: {sorted(set(macros.values()))})"
            )

    return by_class


# --------------------------------------------------------------------------
# 3. Java parsing
# --------------------------------------------------------------------------

def java_path(fqn: str) -> Path:
    return JAVA_ROOT / Path(*fqn.split(".")).with_suffix(".java")


def parse_java(fqn: str) -> dict:
    """Return the methods declared by a Java class, keyed by (name, signature)."""
    path = java_path(fqn)
    text = strip_comments(read(path))
    if not text:
        return {"native": {}, "all": {}, "package": "", "declared_class": ""}

    pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
    package = pkg.group(1) if pkg else ""
    if package != fqn.rsplit(".", 1)[0]:
        fail(
            f"{path.relative_to(ROOT)} declares package "
            f"'{package or '<none>'}', expected '{fqn.rsplit('.', 1)[0]}'"
        )

    cls = re.search(r"\b(?:class|interface|enum)\s+(\w+)", text)
    declared = cls.group(1) if cls else ""
    if declared != fqn.rsplit(".", 1)[-1]:
        fail(
            f"{path.relative_to(ROOT)} declares '{declared or '<nothing>'}', "
            f"expected class name '{fqn.rsplit('.', 1)[-1]}'"
        )

    methods: dict[tuple[str, str], dict] = {}
    native: dict[tuple[str, str], dict] = {}

    decl = re.compile(
        r"(?P<mods>(?:(?:public|protected|private|static|final|native|abstract|"
        r"synchronized|strictfp|default|transient|volatile)\s+)+)"
        r"(?P<ret>[\w.$]+(?:\s*<[^>{}]*>)?(?:\s*\[\s*\])*)\s+"
        r"(?P<name>\w+)\s*\((?P<params>[^)]*)\)\s*(?:throws\s+[\w.,\s]+?)?\s*[;{]"
    )

    for m in decl.finditer(text):
        mods = m.group("mods")
        name = m.group("name")
        if name in ("if", "for", "while", "switch", "catch", "return", "new", "synchronized"):
            continue
        params = m.group("params").strip()
        try:
            sig = to_jni_signature(params, m.group("ret"), package)
        except ValueError as exc:
            fail(f"{fqn}.{name}: cannot read declaration ({exc})")
            continue
        entry = {"name": name, "mods": mods, "line": text.count("\n", 0, m.start()) + 1}
        methods[(name, sig)] = entry
        if "native" in mods.split():
            native[(name, sig)] = entry

    return {
        "native": native,
        "all": methods,
        "package": package,
        "declared_class": declared,
    }


def to_jni_signature(params: str, ret: str, package: str) -> str:
    """Convert a Java declaration's types to a JNI signature string."""
    out = ["("]
    if params:
        for param in split_params(params):
            cleaned = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", param).strip()
            if not cleaned:
                continue
            # Drop parameter names: the type is everything before the last word.
            tokens = cleaned.split()
            if len(tokens) < 2:
                raise ValueError(f"cannot find a type and a name in '{param}'")
            jtype = " ".join(tokens[:-1])
            out.append(java_type_to_jni(jtype, package))
    out.append(")")
    out.append(java_type_to_jni(ret, package))
    return "".join(out)


def split_params(params: str) -> list[str]:
    parts, depth, current = [], 0, []
    for ch in params:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current))
    return parts


def java_type_to_jni(jtype: str, package: str) -> str:
    jtype = jtype.strip().replace(" ", "")
    depth = 0
    array = 0
    for ch in jtype:
        if ch == "[":
            array += 1
        elif ch == "]":
            depth -= 1
    base = jtype.replace("[", "").replace("]", "")
    base = base.split("<", 1)[0]

    if base in PRIMITIVES:
        code = PRIMITIVES[base]
    elif base == "String":
        code = "Ljava/lang/String;"
    elif base.startswith(("java.lang.", "android.", "androidx.")) and "." in base:
        code = "L" + base.replace(".", "/") + ";"
    elif "." in base:
        code = "L" + base.replace(".", "/") + ";"
    else:
        # An unqualified reference type: resolved against the file's package.
        # Framework types are fully qualified in real signatures, so anything
        # landing here genuinely lives beside the class we are parsing.
        if not package:
            raise ValueError(f"unqualified type '{base}' with no package to resolve it against")
        code = "L" + package.replace(".", "/") + "/" + base + ";"

    return "[" * array + code


# --------------------------------------------------------------------------
# 4. Method lookups in native code
# --------------------------------------------------------------------------

LOOKUP = re.compile(
    r"Get(Static)?MethodID\s*\(\s*(?:env\s*,\s*)?([\w.]+)\s*,\s*"
    r"\"([^\"]+)\"\s*,\s*\"([^\"]+)\"\s*\)",
    re.S,
)


def parse_lookups(sources: dict[Path, str]) -> list[tuple[Path, str, str, str, bool]]:
    """Every (file, class variable, method name, signature, static?) lookup.

    Two spellings appear in the tree and both have to be covered: SDL_android.c
    calls through the function pointer ((*env)->GetStaticMethodID(env, cls, ...)),
    hid.cpp calls through the object (env->GetMethodID(cls, ...)). The optional
    leading `env,` is what tells them apart.
    """
    found = []
    for path, raw in sources.items():
        src = strip_comments(raw)
        for m in LOOKUP.finditer(src):
            found.append((path, m.group(2).strip(), m.group(3), m.group(4), bool(m.group(1))))
    return found


def check_derived_class_vars(macros: dict[str, str]) -> None:
    """Confirm each CLASS_VAR really is fed the class we claim it holds.

    The mapping in CLASS_VARS is written down by hand, so it is checked here
    against the code: a variable is only bound to a class if some
    nativeSetupJNI takes that class as its second argument and stores it, and
    the macro that nativeSetupJNI is named after has to name the same class the
    table registers under.
    """
    src = strip_comments(read(ROOT / "src" / "core" / "android" / "SDL_android.c"))

    bodies = re.findall(
        r"JNICALL\s+(\w+)\s*(?:\(\s*nativeSetupJNI\s*\))?\s*"
        r"\(\s*JNIEnv\s*\*\s*env\s*,\s*jclass\s+cls\s*\)\s*\{(.*?)\n\}",
        src,
        re.S,
    )

    for var, fqn in sorted(CLASS_VARS.items()):
        if var.startswith("g_"):
            continue  # hid.cpp: its class comes from the object it registered

        bound_to = [macro for macro, body in bodies if re.search(rf"\b{re.escape(var)}\s*=", body)]
        if not bound_to:
            fail(f"{var} is never assigned inside a nativeSetupJNI(JNIEnv, jclass) in SDL_android.c")
            continue

        simple = fqn.rsplit(".", 1)[-1]
        named = [macros.get(m) for m in bound_to]
        if simple not in named:
            fail(
                f"CLASS_VARS maps {var} to {fqn}, but native code binds {var} in a "
                f"function named by {sorted(bound_to)}, which name class "
                f"{sorted(x for x in named if x)}"
            )
        elif len(set(named)) > 1:
            fail(f"{var} is assigned by more than one class: {sorted(set(named))}")


# --------------------------------------------------------------------------
# 5. The checks themselves
# --------------------------------------------------------------------------

def run(quiet: bool) -> int:
    prefix, macros = parse_naming()
    package = prefix_to_package(prefix)
    expected_package = "me.shadow.eclipselauncher.sdl"

    if prefix and prefix != expected_package.replace(".", "_"):
        fail(
            f"SDL_JAVA_PREFIX is '{prefix}', expected "
            f"'{expected_package.replace('.', '_')}' for the Eclipse bindings"
        )

    sources = {p: read(p) for p in NATIVE_SOURCES}
    kinds = parse_native_kinds(sources)
    tables: dict[str, list[tuple[str, str, str]]] = {}
    registrations: dict[str, str] = {}
    for src in sources.values():
        if not src:
            continue
        tables.update(parse_tables(src))
        registrations.update(parse_registrations(src))

    by_class = check_registered_natives(tables, registrations, package, macros, kinds)
    check_derived_class_vars(macros)

    lookups = parse_lookups(sources)

    java_cache: dict[str, dict] = {}
    for fqn in sorted(set(list(by_class) + list(CLASS_VARS.values()))):
        if fqn not in java_cache:
            java_cache[fqn] = parse_java(fqn)

    # --- 1 & 3: registered natives -------------------------------------
    for fqn, entries in sorted(by_class.items()):
        info = java_cache.get(fqn)
        if info is None:
            continue
        for name, sig, kind in entries:
            declared = info["native"].get((name, sig))
            if declared is None:
                if (name, sig) in info["all"]:
                    fail(
                        f"{fqn}.{name} exists in Java but is not declared `native`; "
                        f"JNI signature required: {sig}"
                    )
                elif any(k[0] == name for k in info["all"]):
                    got = [k[1] for k in info["all"] if k[0] == name]
                    fail(
                        f"{fqn}.{name} has the wrong signature: native wants {sig}, "
                        f"Java has {', '.join(sorted(set(got)))}"
                    )
                else:
                    fail(f"{fqn}.{name}{sig} is registered by native code but missing from Java")
                continue

            mods = declared["mods"].split()
            want_static = kind == "static"
            if ("static" in mods) != want_static:
                expected = "a `static native` method" if want_static else "an instance `native` method"
                fail(
                    f"{fqn}.{name} must be {expected}: its C function takes "
                    f"{'jclass' if want_static else 'jobject'} as the second argument"
                )

        for (name, sig) in info["native"]:
            if not any((name, sig) == e[:2] for e in entries):
                fail(
                    f"{fqn}.{name}{sig} is declared native in Java but never registered "
                    f"by a JNINativeMethod table — calling it throws UnsatisfiedLinkError"
                )

    # --- 2: method lookups ---------------------------------------------
    checked = 0
    for path, var, name, sig, is_static in lookups:
        fqn = CLASS_VARS.get(var)
        if fqn is None:
            continue  # a framework class obtained by FindClass/GetObjectClass
        info = java_cache.get(fqn)
        if info is None:
            continue
        checked += 1

        if not is_static and var in STATIC_ONLY_VARS:
            fail(
                f"{path.relative_to(ROOT)} looks up the instance method {var}.{name}{sig}, but "
                f"native code only ever holds {var} as a Class — it has no instance to call it "
                f"on. Use GetStaticMethodID, or look the method up on the object itself."
            )
            continue

        key = (name, sig)
        if key in info["all"]:
            mods = info["all"][key]["mods"].split()
            is_java_static = "static" in mods
            # GetStaticMethodID on an instance method, or GetMethodID on a
            # static one, raises NoSuchMethodError the moment it is called —
            # which is on a frame the app already shipped.
            if is_static and not is_java_static:
                fail(
                    f"{path.relative_to(ROOT)} uses GetStaticMethodID for {fqn}.{name}, "
                    f"but Java declares it as an instance method"
                )
            elif not is_static and is_java_static:
                fail(
                    f"{path.relative_to(ROOT)} uses GetMethodID for {fqn}.{name}, "
                    f"but Java declares it static"
                )
            continue
        if any(k[0] == name for k in info["all"]):
            got = [k[1] for k in info["all"] if k[0] == name]
            fail(
                f"{path.relative_to(ROOT)} looks up {var}.{name} with signature {sig}, "
                f"but {fqn}.{name} has {', '.join(sorted(set(got)))}"
            )
        else:
            fail(
                f"{path.relative_to(ROOT)} looks up {var}.{name}{sig} on {fqn}, "
                f"but that method does not exist in Java"
            )

    # --- summary --------------------------------------------------------
    natives = sum(len(v) for v in by_class.values())
    if not quiet:
        print(f"package            {package}")
        print(f"classes bound      {len(by_class)}")
        print(f"registered natives {natives}")
        print(f"method lookups     {checked}")

    if problems:
        print(f"\nFAIL — {len(problems)} problem(s) between native and Java:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1

    print("OK — native tables, method lookups and Java declarations agree.")
    return 0


def emit_contract(by_class: dict, lookups) -> str:
    """Write the binding contract out as markdown.

    The same numbers the checker will later enforce, so whoever writes the Java
    works from one source of truth instead of transcribing JNI tables by hand —
    which is exactly the kind of transcription error this tool exists to catch.
    """
    by_fqn: dict[str, dict[str, list[tuple[str, str]]]] = {}
    for fqn, entries in by_class.items():
        by_fqn.setdefault(fqn, {"native": [], "static": [], "instance": []})["native"].extend(
            entries
        )

    for _path, var, name, sig, is_static in lookups:
        fqn = CLASS_VARS.get(var)
        if fqn is None:
            continue
        bucket = by_fqn.setdefault(fqn, {"native": [], "static": [], "instance": []})
        bucket["static" if is_static else "instance"].append((name, sig))

    out = [
        "# Java ⇄ native binding contract",
        "",
        "Generated by `tools/check_jni_bindings.py --emit-contract`; do not edit",
        "this file by hand and do not treat it as documentation — it is the list",
        "of things the checker will fail the build over.",
        "",
        "Signatures are JNI: `(argument types)return type`, where `I` is int,",
        "`Z` boolean, `F` float, `J` long, `C` char, `B` byte, `S` short, `V`",
        "void, `[X` is an array of X, and `Lpkg/Name;` is a class.",
        "",
    ]

    for fqn in sorted(by_fqn):
        bucket = by_fqn[fqn]
        out.append(f"## {fqn}")
        out.append("")
        if bucket["native"]:
            out.append("### native methods (registered by JNI_OnLoad)")
            out.append("")
            out.append("| method | JNI signature | declared in Java as |")
            out.append("|---|---|---|")
            for name, sig, kind in sorted(set(bucket["native"])):
                how = "`static native`" if kind == "static" else "instance `native`"
                out.append(f"| `{name}` | `{sig}` | {how} |")
            out.append("")
        for kind, heading in (
            ("static", "static methods native code calls"),
            ("instance", "instance methods native code calls"),
        ):
            if not bucket[kind]:
                continue
            out.append(f"### {heading}")
            out.append("")
            out.append("| method | JNI signature |")
            out.append("|---|---|")
            for name, sig in sorted(set(bucket[kind])):
                out.append(f"| `{name}` | `{sig}` |")
            out.append("")

    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only report failures")
    parser.add_argument(
        "--emit-contract",
        nargs="?",
        const="-",
        metavar="PATH",
        help="write the binding contract as markdown to PATH (or stdout) and exit",
    )
    args = parser.parse_args()

    if args.emit_contract:
        prefix, macros = parse_naming()
        sources = {p: read(p) for p in NATIVE_SOURCES}
        kinds = parse_native_kinds(sources)
        tables: dict[str, list[tuple[str, str, str]]] = {}
        registrations: dict[str, str] = {}
        for src in sources.values():
            if not src:
                continue
            tables.update(parse_tables(src))
            registrations.update(parse_registrations(src))
        by_class = check_registered_natives(
            tables, registrations, prefix_to_package(prefix), macros, kinds
        )
        check_derived_class_vars(macros)
        lookups = parse_lookups(sources)
        text = emit_contract(by_class, lookups)
        if args.emit_contract == "-":
            print(text)
        else:
            Path(args.emit_contract).write_text(text + "\n", encoding="utf-8")
        if problems:
            for p in problems:
                print(f"  - {p}", file=sys.stderr)
        return 0 if not problems else 1

    return run(args.quiet)


if __name__ == "__main__":
    sys.exit(main())
