#!/usr/bin/env python3
"""Reject an unbound three-repository install before stopping the local stack."""

import json
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path


ORIGINS = {
    "backend": "https://github.com/panzhencheng666-gif/cofco-qiqihar-enterprise-backend.git",
    "frontend": "https://github.com/panzhencheng666-gif/cofco-qiqihar-enterprise-frontend.git",
    "web": "https://github.com/panzhencheng666-gif/cofco-qiqihar-enterprise-web.git",
}
SHA = re.compile(r"^[0-9a-f]{40}$")


def command(*args, cwd=None, timeout=20):
    result = subprocess.run(
        args,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    if result.returncode:
        raise ValueError(f"Command failed before install: {args[0]} {args[1]}")
    return (result.stdout or result.stderr).strip()


def verify(manifest_path, workspace):
    if not manifest_path:
        raise ValueError("COFCO_ENTERPRISE_RELEASE_MANIFEST_PATH is required")
    manifest = Path(manifest_path)
    if manifest.is_symlink() or not manifest.is_file():
        raise ValueError("Release manifest must be an existing regular file")
    manifest_digest = hashlib.sha256(manifest.read_bytes()).digest()
    web = workspace / "cofco-qiqihar-enterprise-web"
    cli = web / "scripts/release-manifest-cli.mjs"
    command("node", str(cli), "validate", "--manifest", str(manifest))
    envelope = json.loads(manifest.read_text(encoding="utf-8"))
    release = envelope["manifest"]
    if release["environment"] not in ("candidate", "preproduction-candidate"):
        raise ValueError("Release fixture is not eligible for formal install")

    for name, origin in ORIGINS.items():
        repo = workspace / f"cofco-qiqihar-enterprise-{name}"
        if repo.is_symlink() or not repo.is_dir():
            raise ValueError(f"{name} source must be a real sibling repository")
        if command("git", "rev-parse", "--show-toplevel", cwd=repo) != str(repo.resolve()):
            raise ValueError(f"{name} source is not the Git repository root")
        if command("git", "status", "--porcelain=v1", "--untracked-files=all", cwd=repo):
            raise ValueError(f"{name} source has uncommitted files")
        binding = release["repositories"][name]
        if binding["origin"] != origin or binding["ref"] not in ("main", "refs/heads/main"):
            raise ValueError(f"{name} manifest is not bound to official main")
        if command("git", "remote", "get-url", "origin", cwd=repo) != origin:
            raise ValueError(f"{name} Git origin is not official")
        head = command("git", "rev-parse", "HEAD", cwd=repo)
        remote = command("git", "ls-remote", "--heads", "origin", "refs/heads/main", cwd=repo)
        remote_parts = remote.split()
        if len(remote_parts) != 2 or remote_parts[1] != "refs/heads/main" or not SHA.fullmatch(remote_parts[0]):
            raise ValueError(f"{name} official main could not be resolved")
        if head != binding["commitSha"] or head != remote_parts[0]:
            raise ValueError(f"{name} source commit differs from official main or manifest")

    backend = workspace / "cofco-qiqihar-enterprise-backend"
    jdk_home = command(
        "bash", "-c", 'source "$1"; printf "%s" "$JAVA_HOME"',
        "bash", str(backend / "scripts/jdk21-env.sh"),
    )
    jdk_version = command(str(Path(jdk_home) / "bin/java"), "-version")
    version = re.search(r'version "([^"]+)"', jdk_version)
    if version is None:
        raise ValueError("JDK version could not be read")
    command(
        "node", str(cli), "verify", "--manifest", str(manifest),
        "--backend-root", str(backend),
        "--frontend-root", str(workspace / "cofco-qiqihar-enterprise-frontend"),
        "--web-root", str(web),
        "--node-version", command("node", "--version"),
        "--npm-version", command("npm", "--version"),
        "--jdk-version", version.group(1),
        timeout=120,
    )
    if hashlib.sha256(manifest.read_bytes()).digest() != manifest_digest:
        raise ValueError("Release manifest changed during verification")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("One source workspace path is required")
        workspace = Path(sys.argv[1])
        verify(os.environ.get("COFCO_ENTERPRISE_RELEASE_MANIFEST_PATH"), workspace)
    except (KeyError, OSError, ValueError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        print(f"Release source preflight failed: {error}", file=sys.stderr)
        sys.exit(1)
    print("Release source preflight passed")
