#!/usr/bin/env python3
"""Read-only ADB bridge to a debuggable Breadcrumb install."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

PACKAGE = "io.github.valeronm.breadcrumb.debug"
RECEIVER = "io.github.valeronm.breadcrumb.debug.DebugBridgeReceiver"
ACTION = "io.github.valeronm.breadcrumb.DEBUG_BRIDGE"
REMOTE_DIR = "cache/debug-bridge"
DEFAULT_TIMEOUT = 20


class BridgeError(RuntimeError):
    pass


def run(command: list[str], *, timeout: float = 15, stdout=None) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            command,
            check=True,
            text=stdout is None,
            stdout=subprocess.PIPE if stdout is None else stdout,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise BridgeError("Nie znaleziono adb. Zainstaluj Android Platform Tools i dodaj adb do PATH.") from exc
    except subprocess.CalledProcessError as exc:
        raw_detail = exc.stderr or ""
        detail = raw_detail.decode(errors="replace").strip() if isinstance(raw_detail, bytes) else raw_detail.strip()
        raise BridgeError(detail or f"Polecenie ADB zakończyło się kodem {exc.returncode}.") from exc
    except subprocess.TimeoutExpired as exc:
        raise BridgeError("ADB nie odpowiedziało w wyznaczonym czasie.") from exc


def find_adb() -> str:
    configured = os.environ.get("ADB")
    if configured:
        candidate = shutil.which(configured) if not Path(configured).is_file() else configured
        if candidate:
            return candidate
    on_path = shutil.which("adb")
    if on_path:
        return on_path

    sdk_dirs = [Path(value) for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME") if (value := os.environ.get(key))]
    properties = Path(__file__).resolve().parents[1] / "local.properties"
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk_dirs.insert(0, Path(line.partition("=")[2].replace("\\:", ":")))
                break
    sdk_dirs.extend((Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"))
    for sdk_dir in sdk_dirs:
        candidate = sdk_dir / "platform-tools" / "adb"
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise BridgeError("Nie znaleziono adb. Ustaw ADB/ANDROID_SDK_ROOT lub zainstaluj Android Platform Tools.")


def adb_prefix(serial: str | None) -> list[str]:
    adb = find_adb()
    prefix = [adb]
    if serial:
        prefix += ["-s", serial]
    else:
        devices = run([adb, "devices"], timeout=10).stdout.splitlines()[1:]
        connected = [line.split()[0] for line in devices if "\tdevice" in line]
        if not connected:
            raise BridgeError("Brak urządzenia ADB. Podłącz Pixela przez USB lub Wireless Debugging.")
        if len(connected) > 1:
            raise BridgeError("Jest kilka urządzeń ADB. Wskaż jedno przez --serial.")
        prefix += ["-s", connected[0]]
    return prefix


def check_install(adb: list[str]) -> None:
    try:
        result = run(adb + ["shell", "run-as", PACKAGE, "id"], timeout=10)
    except BridgeError as exc:
        raise BridgeError(
            f"Nie można użyć run-as dla {PACKAGE}. Zainstaluj wariant debug (nie perf/release) "
            "i upewnij się, że urządzenie jest odblokowane. Szczegóły: " + str(exc)
        ) from exc
    if "uid=" not in result.stdout:
        raise BridgeError("Zainstalowana aplikacja nie jest debuggable; wymagany jest wariant debug.")


def remote_request(adb: list[str], args: argparse.Namespace) -> dict:
    request_id = str(uuid.uuid4())
    remote_file = f"{REMOTE_DIR}/{request_id}.json"
    command = [
        *adb, "shell", "am", "broadcast", "-n", f"{PACKAGE}/{RECEIVER}", "-a", ACTION,
        "--es", "requestId", request_id, "--es", "command", args.command,
    ]
    if args.command == "day":
        command += ["--es", "day", args.day]
        if args.timezone:
            command += ["--es", "timezone", args.timezone]
    if args.command == "suggestions":
        if args.state:
            command += ["--es", "state", args.state.upper()]
        command += ["--ei", "limit", str(args.limit)]
    broadcast = run(command, timeout=DEFAULT_TIMEOUT)
    if any(marker in broadcast.stdout for marker in ("Permission Denial", "SecurityException", "Error:")):
        raise BridgeError("Android odrzucił żądanie do debug bridge'a: " + broadcast.stdout.strip())

    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        ready = subprocess.run(adb + ["shell", "run-as", PACKAGE, "test", "-f", remote_file],
                               text=True, capture_output=True, timeout=5)
        if ready.returncode == 0:
            break
        time.sleep(0.2)
    else:
        subprocess.run(adb + ["shell", "run-as", PACKAGE, "rm", "-f", remote_file],
                       text=True, capture_output=True, timeout=5)
        raise BridgeError(f"Aplikacja nie przygotowała odpowiedzi w {args.timeout:g} s. Sprawdź logcat.")

    local_path = Path(tempfile.gettempdir()) / f"breadcrumb-{args.command}-{request_id}.json"
    try:
        with local_path.open("wb") as output:
            run(adb + ["exec-out", "run-as", PACKAGE, "cat", remote_file], timeout=60, stdout=output)
        payload = json.loads(local_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        local_path.unlink(missing_ok=True)
        raise BridgeError(f"Nie udało się odczytać odpowiedzi bridge'a: {exc}") from exc
    finally:
        subprocess.run(adb + ["shell", "run-as", PACKAGE, "rm", "-f", remote_file],
                       text=True, capture_output=True, timeout=5)
    if not payload.get("ok"):
        local_path.unlink(missing_ok=True)
        raise BridgeError(payload.get("error", "Aplikacja zwróciła błąd bez opisu."))
    return {"path": str(local_path), "payload": payload}


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="Read-only diagnostic access to Breadcrumb on a Pixel via ADB.")
    root.add_argument("--serial", help="ADB serial when more than one device is connected")
    root.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="seconds to wait for app response")
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("status", help="analysis queue summary")
    suggestions = commands.add_parser("suggestions", help="review queue entries")
    suggestions.add_argument("--state", help="filter by state, e.g. PENDING or SNOOZED")
    suggestions.add_argument("--limit", type=int, default=200)
    day = commands.add_parser("day", help="full route and sensor diagnostics for a local calendar day")
    day.add_argument("day", help="date as YYYY-MM-DD")
    day.add_argument("--timezone", help="IANA zone; defaults to the Pixel's current timezone")
    commands.add_parser("logs", help="persisted app diagnostic log")
    return root


def validate(args: argparse.Namespace) -> None:
    if args.command == "day":
        try:
            dt.date.fromisoformat(args.day)
            from zoneinfo import ZoneInfo
            if args.timezone:
                ZoneInfo(args.timezone)
        except (ValueError, KeyError) as exc:
            raise BridgeError(f"Nieprawidłowa data lub strefa: {exc}") from exc
    if args.command == "suggestions" and not 1 <= args.limit <= 2000:
        raise BridgeError("--limit musi być w zakresie 1–2000.")
    if args.timeout <= 0:
        raise BridgeError("--timeout musi być większe od zera.")


def main() -> int:
    args = parser().parse_args()
    try:
        validate(args)
        adb = adb_prefix(args.serial)
        check_install(adb)
        result = remote_request(adb, args)
        payload = result["payload"]
        summary = {key: payload[key] for key in ("queued", "running", "failed", "unprocessed", "count", "date")
                   if key in payload}
        print(json.dumps({"output_file": result["path"], "summary": summary}, ensure_ascii=False))
        return 0
    except (BridgeError, subprocess.TimeoutExpired) as exc:
        print(f"breadcrumb-debug: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
