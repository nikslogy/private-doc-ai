#!/usr/bin/env python3
"""Bounded memory-only sampling of the phase-identified synthetic CI test PID."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import threading
import time

MAX_SAMPLES = 900
MAX_REPORT_BYTES = 120_000
MAX_TRANSCRIPT_BYTES = 262_144
MAX_PID = 2_147_483_647
MAX_MEMORY_BYTES = 2**63 - 1
PID_PREFIX = 'INSTRUMENTATION_STATUS: modelProbePid='


def unique_test_pid(transcript: str) -> int | None:
    """Only exact test-emitted numeric metadata can identify a process."""
    pids: set[int] = set()
    for line in transcript.splitlines():
        if not line.startswith(PID_PREFIX):
            continue
        match = re.fullmatch(re.escape(PID_PREFIX) + r'([1-9][0-9]{0,9})', line)
        if match is None or int(match[1]) > MAX_PID:
            raise ValueError('Invalid test PID metadata')
        pids.add(int(match[1]))
    if len(pids) > 1:
        raise ValueError('Ambiguous test PID metadata')
    return next(iter(pids)) if pids else None


def parse_process_memory_status(text: str) -> dict[str, int]:
    """Discard every /proc field except strictly numeric VmRSS/VmHWM values."""
    values: dict[str, int] = {}
    names = {'VmRSS': 'rssBytes', 'VmHWM': 'hwmBytes'}
    for line in text.splitlines():
        if not any(line.startswith(name + ':') for name in names):
            continue
        match = re.fullmatch(r'(VmRSS|VmHWM):\s+([0-9]{1,19})\s+kB', line)
        if match is None or match[1] in values:
            raise ValueError('Invalid or duplicate process memory field')
        value = int(match[2]) * 1024
        if value > MAX_MEMORY_BYTES:
            raise ValueError('Process memory field exceeds numeric bound')
        values[match[1]] = value
    if set(values) != set(names):
        raise ValueError('Required numeric process memory fields are absent')
    return {names[name]: value for name, value in values.items()}


def adb(serial: str, *arguments: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(['adb', '-s', serial, *arguments], capture_output=True,
                          timeout=3, check=False)


def write_report(path: Path, report: dict) -> None:
    encoded = (json.dumps(report, separators=(',', ':')) + '\n').encode()
    if len(encoded) > MAX_REPORT_BYTES or path.is_symlink() or path.parent.is_symlink():
        raise ValueError('Memory report path/size validation failed')
    temporary: str | None = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.memory-samples-',
                                         delete=False) as stream:
            temporary = stream.name
            stream.write(encoded)
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--transcript', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    stopped = threading.Event()
    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, lambda _signum, _frame: stopped.set())
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted'
            or args.serial != 'emulator-5554'
            or not re.fullmatch(r'com\.privateassistant\.platform(?:\.[A-Za-z0-9_]+)*\.test', args.package)):
        parser.error('Sampling is restricted to the disposable synthetic CI emulator')
    for property_name, expected in [('ro.kernel.qemu', b'1'), ('ro.build.version.sdk', b'36')]:
        result = adb(args.serial, 'shell', 'getprop', property_name)
        if result.returncode != 0 or result.stdout.strip() != expected:
            parser.error('Expected synthetic API36 emulator is unavailable')
    if (args.transcript.is_symlink() or not args.transcript.is_file()
            or args.output.is_symlink() or args.output.parent.is_symlink()):
        parser.error('Report/transcript paths must be regular owned files')
    started = time.monotonic()
    pid: int | None = None
    samples: list[list[int]] = []
    read_errors = 0
    timeout_errors = 0
    metadata_rejected = 0
    next_sample = started
    try:
        while (not stopped.is_set() and len(samples) < MAX_SAMPLES
               and time.monotonic() - started < 900):
            if args.transcript.stat().st_size > MAX_TRANSCRIPT_BYTES:
                raise ValueError('Test transcript exceeds metadata sampling bound')
            transcript = args.transcript.read_text()
            # A tee write can be observed mid-line; incomplete metadata cannot
            # identify a different PID or invalidate an otherwise complete line.
            if not transcript.endswith('\n'):
                transcript = transcript.rsplit('\n', 1)[0] + '\n' if '\n' in transcript else ''
            selected_pid = unique_test_pid(transcript)
            if selected_pid is not None:
                if pid is not None and pid != selected_pid:
                    raise ValueError('Test PID changed during sampling')
                pid = selected_pid
            if pid is None:
                stopped.wait(0.25)
                continue
            if time.monotonic() < next_sample:
                stopped.wait(min(0.25, next_sample - time.monotonic()))
                continue
            next_sample = time.monotonic() + 1
            try:
                result = adb(args.serial, 'shell', 'run-as', args.package,
                             'cat', f'/proc/{pid}/status')
                if result.returncode != 0 or len(result.stdout) > 16_384:
                    read_errors += 1
                    continue
                memory = parse_process_memory_status(result.stdout.decode('ascii'))
                samples.append([int((time.monotonic() - started) * 1000),
                                memory['rssBytes'], memory['hwmBytes']])
            except subprocess.TimeoutExpired:
                timeout_errors += 1
            except (ValueError, UnicodeError):
                read_errors += 1
    except (ValueError, OSError):
        metadata_rejected = 1
    finally:
        write_report(args.output, {
            'schemaVersion': 1, 'scope': 'Periodic numeric memory of only the phase-identified synthetic CI test PID; observed samples can miss peaks and do not establish a phone RAM minimum.',
            'emittedPid': pid or 0, 'sampleIntervalMillis': 1000, 'sampleLimit': MAX_SAMPLES,
            'sampleCount': len(samples), 'sampleColumns': ['elapsedMillis', 'rssBytes', 'hwmBytes'],
            'samples': samples, 'readErrors': read_errors, 'timeoutErrors': timeout_errors,
            'metadataRejected': metadata_rejected,
            'observedMaxRssBytes': max((sample[1] for sample in samples), default=0),
            'observedMaxHwmBytes': max((sample[2] for sample in samples), default=0),
        })
    return 1 if metadata_rejected else 0


if __name__ == '__main__':
    raise SystemExit(main())
