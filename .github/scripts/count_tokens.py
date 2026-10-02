"""Estimates the Plugin Hub review bot's token count for this plugin, and fails if it is too close
to the bot's limit.

The bot reads everything under src/main/java with comments stripped, and stops reviewing at 200,000
tokens. Its tokenizer is not public; tiktoken's cl100k_base counts about 3% under it, so the estimate
is that times 1.03.

    python .github/scripts/count_tokens.py [--fail-at 195000] [--warn-at 185000]

Writes a summary to $GITHUB_STEP_SUMMARY when run in GitHub Actions.
"""
import argparse
import os
import pathlib
import re
import sys

import tiktoken

ENC = tiktoken.get_encoding("cl100k_base")
BOT_RATIO = 1.03


def strip_comments(src):
    """Java source without // and /* */ comments, leaving string, text block and char literals alone."""
    out = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j])
            i = j
        elif src[i] in "\"'":
            quote = src[i]
            j = i + 1
            while j < n and src[j] != quote:
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(src[i:j])
            i = j
        else:
            out.append(src[i])
            i += 1
    text = "\n".join(line.rstrip() for line in "".join(out).split("\n"))
    # Lines that held only a comment leave blank runs behind.
    return re.sub(r"\n[ \t]*\n(?:[ \t]*\n)+", "\n\n", text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="src/main/java")
    parser.add_argument("--fail-at", type=int, default=195_000)
    parser.add_argument("--warn-at", type=int, default=185_000)
    args = parser.parse_args()

    counts = []
    for path in sorted(pathlib.Path(args.root).rglob("*.java")):
        text = strip_comments(path.read_text(encoding="utf-8"))
        counts.append((len(ENC.encode(text, disallowed_special=())), path.as_posix()))
    total = sum(c for c, _ in counts)
    bot = round(total * BOT_RATIO)

    if bot > args.fail_at:
        status, level = f"over the {args.fail_at:,} budget", "error"
    elif bot > args.warn_at:
        status, level = f"over the {args.warn_at:,} warning line", "warning"
    else:
        status, level = "within budget", None

    print(f"{len(counts)} files, {total:,} tokens (cl100k), about {bot:,} as the review bot counts: {status}")
    if level:
        print(f"::{level} title=Review bot token budget::About {bot:,} of 200,000 tokens ({status})")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("### Review bot token budget\n\n")
            f.write(f"**About {bot:,} of 200,000** ({total:,} cl100k tokens x {BOT_RATIO}, comments stripped): {status}. ")
            f.write(f"Room left before the {args.fail_at:,} line: **{args.fail_at - bot:,}**.\n\n")
            f.write("| File | Tokens |\n|---|---:|\n")
            for c, p in sorted(counts, reverse=True)[:15]:
                f.write(f"| `{p}` | {c:,} |\n")
            f.write("\n")

    return 1 if level == "error" else 0


if __name__ == "__main__":
    sys.exit(main())
