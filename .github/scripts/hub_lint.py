"""Checks the plugin for what Plugin Hub reviewers have sent plugins back for.

The hub's own packager checks banned APIs, sizes and the properties file; this covers what reviewers
and the review bot flag by hand, from their comments on runelite/plugin-hub pull requests (numbers
below). Code under src/main/java is what ships and is held to all of it; tests are not shipped but
are in the repository reviewers read, so the worst of it is a warning there.

    python .github/scripts/hub_lint.py

A line can be excused with a comment on it: // hub-lint: allow <rule>, with the reason beside it.
Exits 1 if anything is an error. Prints GitHub annotations, so findings show on the changed lines.
"""
import pathlib
import re
import struct
import subprocess
import sys

ERROR, WARNING = "error", "warning"

# The plugin's file in runelite/plugin-hub's plugins folder, which internalName must match.
HUB_NAME = "golems-dont-die"

# The hub's wording for a plugin that adds networking, which its setting's warning must include (#16809).
REQUIRED_WARNING = ("This feature submits your IP address and various account data to a 3rd-party server "
                    "not controlled or verified by Runelite developers.")

# (rule, pattern, why, severity in src/main, severity in src/test or None)
CODE_RULES = [
    ("sleep", r"\bThread\.sleep\b|\.interrupt\(\)|\bawaitTermination\b",
     "No sleeping, interrupting or waiting on threads (#11086, #14949, #11784)", ERROR, None),
    ("reflection", r"\bjava\.lang\.reflect\b|\bgetDeclared(Field|Method|Constructor)s?\(|\bsetAccessible\(|\bClass\.forName\b|\bMethodHandles\b",
     "Reflection sends a plugin to manual review", ERROR, WARNING),
    ("client-api", r"\.menuAction\(|\.runScript\(|\bhopToWorld\b",
     "menuAction, runScript and hopToWorld are restricted (#11371, #14785, #13642)", ERROR, None),
    ("browser", r"\bDesktop\b|\bLinkBrowser\b",
     "Opening a browser or the desktop is restricted (#15103)", ERROR, None),
    ("focus", r"\bKeyboardFocusManager\b|\bsetAlwaysOnTop\b|\btoFront\(\)|\brequestFocus\(\)",
     "Changing window or keyboard focus is a rejected feature (#14727, #13083, #16492)", ERROR, None),
    ("process", r"\bRuntime\.getRuntime\b|\bProcessBuilder\b|\bSystem\.getenv\b|\bSystem\.exit\b"
                r"|\bVarHandle\b|\bavailableProcessors\b|\bjava\.lang\.management\b",
     "No processes, environment, VarHandles or runtime inspection (#14211, #17210, #17424)", ERROR, WARNING),
    ("resources", r"\.getResource\(",
     "Load resources with getResourceAsStream, not getResource (#16533, #17232)", ERROR, None),
    ("sound", r"\bjavax\.sound\b",
     "Play sounds through the client's AudioPlayer, not javax.sound (review bot)", ERROR, None),
    ("filepath-unchecked", r"\bUnchecked\b",
     "Filepath.Unchecked sends a plugin to manual review (#17181)", ERROR, None),
    ("enabled-by-default", r"enabledByDefault\s*=\s*false",
     "enabledByDefault = false is not allowed (review bot)", ERROR, None),
    ("gpu", r"\borg\.lwjgl\b|\bjogamp\b|\bjogl\b",
     "New GPU or LWJGL code is not being accepted (#16170, #16409)", ERROR, None),
    ("file-io", r"\bnew File\(|\bFile(Input|Output)Stream\b|\bFile(Reader|Writer)\b|\bPrintWriter\b|\bRandomAccessFile\b"
                r"|\bjava\.nio\.file\b|\bFiles\.|\bPaths\.get\b|\bRUNELITE_DIR\b|\bImageIO\.write\b",
     "All file I/O through Filepath and getPluginDirectory() (#16712, #16725, #16027)", ERROR, WARNING),
    ("client-singletons", r"\bnew OkHttpClient\b|\bOkHttpClient\.Builder\(|\bnew Gson\(|\bnew GsonBuilder\b",
     "Inject the client's OkHttpClient and Gson; never make new ones (packager)", ERROR, None),
    ("widgets", r"\bWidgetInfo\b|\bWidgetID\b",
     "Use InterfaceID or ComponentID, not WidgetInfo or WidgetID (packager)", ERROR, None),
    ("console", r"\bSystem\.(out|err)\b|\.printStackTrace\(\)",
     "Log through the logger, not the console", ERROR, WARNING),
    ("threads", r"\bnew Thread\(|\bExecutors\.new|\bnew (Scheduled)?ThreadPoolExecutor\b",
     "Own threads draw questions; prefer the injected ScheduledExecutorService, or explain in the PR", WARNING, None),
    ("log-info", r"\blog\.info\(",
     "info-level logs fill players' client logs; prefer debug", WARNING, None),
]

# Matched against code with comments removed but strings kept.
STRING_RULES = [
    ("file-io", r"user\.home|\.runelite[/\\]",
     "No paths into the user's home or .runelite; use getPluginDirectory()", ERROR, WARNING),
]

# Making a request, as opposed to holding the injected client to hand on.
NETWORK = re.compile(r"\.newCall\(|\bRequest\.Builder\b|\bURLConnection\b|\bnew URL\(|\bnew Socket\(|\bHttpUrl\.")
ALLOW = re.compile(r"//\s*hub-lint:\s*allow\s+([\w-]+)")
ALLOWED_DEPENDENCIES = re.compile(r"net\.runelite|org\.projectlombok|lombok|junit|org\.jetbrains:annotations")

problems = []


def report(severity, path, line, rule, message):
    problems.append(severity)
    where = f"file={path},line={line}," if line else (f"file={path}," if path else "")
    print(f"::{severity} {where}title=hub-lint {rule}::{message}")


def blank(src, keep_strings):
    """The source with comments (and, unless kept, string and char literals) blanked out, lines kept."""
    out = []
    i, n = 0, len(src)

    def spaces(text):
        return re.sub(r"[^\n]", " ", text)

    while i < n:
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(spaces(src[i:j]))
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(spaces(src[i:j]))
        elif src.startswith('"""', i) or src[i] in "\"'":
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and src[j] != src[i]:
                    j += 2 if src[j] == "\\" else 1
                j = min(j + 1, n)
            out.append(src[i:j] if keep_strings else src[i] + spaces(src[i + 1:j - 1]) + src[j - 1])
        else:
            out.append(src[i])
            j = i + 1
        i = j
    return "".join(out)


def tracked(root):
    """The Java files under a folder that are in the repository: what reviewers read."""
    out = subprocess.run(["git", "ls-files", "--", root], capture_output=True, text=True, check=True).stdout
    return [pathlib.Path(p) for p in out.splitlines() if p.endswith(".java")]


def lint_java(root, main):
    for path in tracked(root):
        src = path.read_text(encoding="utf-8")
        raw_lines = src.split("\n")
        for keep_strings, rules in ((False, CODE_RULES), (True, STRING_RULES)):
            text = blank(src, keep_strings).split("\n")
            for rule, pattern, why, in_main, in_test in rules:
                severity = in_main if main else in_test
                if severity is None:
                    continue
                hits = []
                for number, line in enumerate(text, 1):
                    if re.search(pattern, line):
                        allowed = ALLOW.search(raw_lines[number - 1])
                        if not (allowed and allowed.group(1) == rule):
                            hits.append(number)
                if main:
                    for number in hits:
                        report(severity, path.as_posix(), number, rule, why)
                elif hits:
                    # Tests are not shipped: once a file is enough.
                    report(severity, path.as_posix(), hits[0], rule,
                           f"{why} ({len(hits)} line(s); tests are not shipped, but reviewers can read them)")
        if main and NETWORK.search(blank(src, False)) and "/telemetry/" not in path.as_posix():
            report(ERROR, path.as_posix(), None, "network",
                   "Network code belongs in the telemetry package, so a reviewer finds all of it in one place")


def properties():
    props = {}
    for line in pathlib.Path("runelite-plugin.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def lint_repo():
    props = properties()
    for key in ("displayName", "author", "description", "tags", "plugins"):
        if not props.get(key):
            report(ERROR, "runelite-plugin.properties", None, "properties", f"{key} must be set")
    if props.get("build", "standard") != "standard":
        report(ERROR, "runelite-plugin.properties", None, "properties",
               "build must be standard: a custom build or new dependencies are effectively never reviewed (#12259)")

    gradle = pathlib.Path("build.gradle").read_text(encoding="utf-8")
    versions = {"runelite-plugin.properties": props.get("version")}
    m = re.search(r"^version\s*=\s*['\"]([^'\"]+)['\"]", gradle, re.M)
    versions["build.gradle"] = m.group(1) if m else None
    for path in tracked("src/main/java"):
        m = re.search(r"static final String VERSION = \"([^\"]+)\"", path.read_text(encoding="utf-8"))
        if m:
            versions[path.as_posix()] = m.group(1)
    if len({v for v in versions.values()}) > 1:
        report(ERROR, None, None, "version",
               "Versions disagree: " + ", ".join(f"{k} {v}" for k, v in versions.items()))

    for m in re.finditer(r"^\s*(implementation|api|runtimeOnly|compileOnly|annotationProcessor)\b.*$", gradle, re.M):
        if not ALLOWED_DEPENDENCIES.search(m.group(0)):
            report(WARNING, "build.gradle", gradle[:m.start()].count("\n") + 1, "dependency",
                   "A new dependency is effectively never reviewed (#12259)")

    if not pathlib.Path("LICENSE").exists():
        report(ERROR, None, None, "license", "A LICENSE file is required, matching the plugin template (#16776)")

    files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.splitlines()
    for path in files:
        if path.startswith(("dev-tools/", "notes/")) or path.endswith(".class") \
                or path.endswith(".jar") and path != "gradle/wrapper/gradle-wrapper.jar":
            report(ERROR, path, None, "tracked",
                   "Dev tools, notes and built files stay out of the repository reviewers read")

    icon = pathlib.Path("icon.png")
    if icon.exists():
        data = icon.read_bytes()
        width, height = struct.unpack(">II", data[16:24])
        if len(data) > 256 * 1024 or width * height > 48 * 72:
            report(ERROR, "icon.png", None, "icon", f"icon.png must be at most 256 KiB and 48x72; it is {width}x{height}")

    main = "\n".join(p.read_text(encoding="utf-8") for p in tracked("src/main/java"))
    m = re.search(r"internalName\s*=\s*\"([^\"]*)\"", main)
    if "getPluginDirectory" in main and not m:
        report(ERROR, None, None, "descriptor", "getPluginDirectory() needs internalName in @PluginDescriptor")
    if m and m.group(1) != HUB_NAME:
        report(ERROR, None, None, "descriptor",
               f"internalName {m.group(1)} must match the hub entry, {HUB_NAME} (packager v4)")
    if NETWORK.search(blank(main, False)):
        if not re.search(r"\bwarning\s*=", main):
            report(ERROR, None, None, "network",
                   "Networking must be opt-in behind a setting with a warning= confirmation (#16809)")
        elif REQUIRED_WARNING not in re.sub(r"\"\s*\+\s*\"", "", main):
            report(ERROR, None, None, "network",
                   f"The networking warning must include the hub's wording: \"{REQUIRED_WARNING}\" (#16809)")

    readme = pathlib.Path("README.md")
    if readme.exists():
        for number, line in enumerate(readme.read_text(encoding="utf-8").splitlines(), 1):
            if re.match(r"#+ .*(build|sideload|from source|install(ing)? manually)", line, re.I):
                report(WARNING, "README.md", number, "readme",
                       "Reviewers ask for sections on building, sideloading or running from source to go (#17123, #17183)")


def main():
    lint_java("src/main/java", True)
    if pathlib.Path("src/test/java").exists():
        lint_java("src/test/java", False)
    lint_repo()
    errors, warnings = problems.count(ERROR), problems.count(WARNING)
    print(f"hub-lint: {errors} error(s), {warnings} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
