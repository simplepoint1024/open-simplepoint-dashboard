#!/usr/bin/env python3
"""Run Gradle gates against disposable loopback-only PostgreSQL and MySQL databases.

Uses cached postgres:17/mysql:8.4 images, random credentials and tmpfs storage. Only
containers created by this invocation are removed. Never connects to business data.
Pass Gradle tasks/options as arguments; JAVA_HOME remains the caller's choice.
"""

import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], text=True).strip()


def run(arguments):
    if not arguments:
        raise ValueError("Provide the Gradle tasks to verify")
    root = Path(__file__).resolve().parents[2]
    containers = []
    environment = dict(os.environ)
    specs = {
        "POSTGRES": ("postgres:17", 5432, "postgres", "/var/lib/postgresql/data"),
        "MYSQL": ("mysql:8.4", 3306, "root", "/var/lib/mysql"),
    }
    try:
        with tempfile.TemporaryDirectory(prefix="simplepoint-dna-tests-") as private:
            for kind, (image, port, user, directory) in specs.items():
                password = secrets.token_hex(24)
                settings = {"POSTGRES_DB": "dna_runtime", "POSTGRES_PASSWORD": password} if kind == "POSTGRES" else {
                    "MYSQL_DATABASE": "dna_runtime", "MYSQL_ROOT_PASSWORD": password, "MYSQL_ROOT_HOST": "%"
                }
                env_file = Path(private) / (kind + ".env")
                env_file.touch(mode=0o600)
                env_file.write_text("\n".join(f"{key}={value}" for key, value in settings.items()) + "\n")
                container = docker("run", "--detach", "--pull=never", "--label", "io.simplepoint.purpose=dna-verification",
                                   "--publish", f"127.0.0.1::{port}", "--tmpfs", f"{directory}:rw,size=1g",
                                   "--env-file", str(env_file), image)
                containers.append((kind, container))
                actual_port = docker("port", container, f"{port}/tcp").rsplit(":", 1)[1]
                prefix = "DNA_RUNTIME_TEST_" + kind
                protocol = "postgresql" if kind == "POSTGRES" else "mysql"
                suffix = "" if kind == "POSTGRES" else "?allowPublicKeyRetrieval=true&useSSL=false"
                environment[prefix + "_URL"] = f"jdbc:{protocol}://127.0.0.1:{actual_port}/dna_runtime{suffix}"
                environment[prefix + "_USER"] = user
                environment[prefix + "_PASSWORD"] = password

            deadline = time.monotonic() + 120
            for kind, container in containers:
                command = ["pg_isready", "-U", "postgres", "-d", "dna_runtime"] if kind == "POSTGRES" else [
                    "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=tcp -h127.0.0.1 -uroot -e "SELECT 1" dna_runtime'
                ]
                while subprocess.run(["docker", "exec", container, *command], stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL).returncode:
                    state = json.loads(docker("inspect", container))[0]["State"]
                    if not state["Running"] or time.monotonic() >= deadline:
                        raise RuntimeError(f"Disposable {kind} failed to become ready")
                    time.sleep(1)
                print(f"Ready: isolated {kind}", flush=True)
            for suffix in ("URL", "USER", "PASSWORD"):
                environment["APP_FORM_TEST_POSTGRES_" + suffix] = environment["DNA_RUNTIME_TEST_POSTGRES_" + suffix]
                environment["WORKFLOW_TEST_POSTGRES_" + suffix] = environment["DNA_RUNTIME_TEST_POSTGRES_" + suffix]
            environment["WORKFLOW_TEST_POSTGRES"] = "1"
            # Environment-dependent tests must rerun even when a prior no-database run was cached.
            init_script = Path(private) / "force-database-tests.gradle"
            init_script.write_text("allprojects { tasks.withType(Test).configureEach { outputs.upToDateWhen { false } } }\n")
            return subprocess.run([str(root / "gradlew"), "--init-script", str(init_script), *arguments],
                                  cwd=root, env=environment).returncode
    finally:
        for kind, container in reversed(containers):
            docker("rm", "--force", "--volumes", container)
            print(f"Removed: isolated {kind}", flush=True)


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:]))
