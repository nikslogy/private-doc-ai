#!/usr/bin/env python3
"""Translate configured, credential-free HTTP proxies into safe JVM properties.

Only public proxy routing properties are written to stdout. Inherited JVM
options, headers, credentials and trust configuration are never read or printed.
"""

from __future__ import annotations

import ipaddress
import os
import re
import sys
from urllib.parse import urlsplit


class ProxyConfigurationError(ValueError):
    """A fixed diagnostic that does not contain supplied proxy values."""


def configured(environment: dict[str, str], name: str) -> str:
    return environment.get(name.lower(), environment.get(name.upper(), ""))


def hostname(value: str) -> str:
    try:
        return str(ipaddress.ip_address(value))
    except ValueError:
        if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9.])?", value):
            raise ProxyConfigurationError("Proxy host must be an ASCII hostname or IP address.") from None
        return value.lower()


def proxy(value: str) -> tuple[str, int]:
    if any(character.isspace() or ord(character) < 32 for character in value):
        raise ProxyConfigurationError("Proxy URL contains unsupported whitespace or control characters.")
    try:
        parsed = urlsplit(value)
        if parsed.username is not None or parsed.password is not None or "@" in parsed.netloc:
            raise ProxyConfigurationError(
                "Authenticated proxy URLs are unsupported for Android Java tools; configure a credential-free session proxy."
            )
        if parsed.scheme != "http":
            raise ProxyConfigurationError("Android Java tools require a configured HTTP proxy URL (http://host:port).")
        if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
            raise ProxyConfigurationError("Proxy URL paths, queries and fragments are unsupported for Android Java tools.")
        if not parsed.hostname:
            raise ProxyConfigurationError("Proxy URL requires a host.")
        port = parsed.port if parsed.port is not None else 80
        if not 1 <= port <= 65535:
            raise ProxyConfigurationError("Proxy port must be between 1 and 65535.")
        return hostname(parsed.hostname), port
    except ValueError as error:
        if isinstance(error, ProxyConfigurationError):
            raise
        raise ProxyConfigurationError("Invalid proxy URL or port for Android Java tools.") from None


def non_proxy_hosts(value: str) -> str:
    result: list[str] = []
    for entry in value.split(","):
        entry = entry.strip()
        if not entry:
            continue
        if entry == "*":
            result.append(entry)
        elif entry.startswith(".") or entry.startswith("*."):
            domain = hostname(entry[2:] if entry.startswith("*.") else entry[1:])
            result.extend((domain, "*." + domain))
        else:
            # Java cannot express CIDR ranges or per-host ports faithfully.
            if "/" in entry:
                raise ProxyConfigurationError("NO_PROXY CIDR entries cannot be represented by Android Java tools.")
            if entry.startswith("[") and entry.endswith("]"):
                try:
                    result.append("[" + str(ipaddress.IPv6Address(entry[1:-1])) + "]")
                    continue
                except ValueError:
                    pass
            try:
                address = ipaddress.ip_address(entry)
                result.append("[" + str(address) + "]" if address.version == 6 else str(address))
                continue
            except ValueError:
                pass
            if ":" in entry:
                raise ProxyConfigurationError("NO_PROXY port entries cannot be represented by Android Java tools.")
            result.append(hostname(entry))
    return "|".join(dict.fromkeys(result))


def jvm_proxy_options(environment: dict[str, str]) -> str:
    options: list[str] = []
    for scheme in ("http", "https"):
        value = configured(environment, scheme + "_proxy") or configured(environment, "all_proxy")
        if value:
            host, port = proxy(value)
            options.extend((f"-D{scheme}.proxyHost={host}", f"-D{scheme}.proxyPort={port}"))
    if options:
        # An empty explicit property also avoids Java's implicit localhost
        # exceptions when the inherited environment specifies no exemptions.
        bypass = non_proxy_hosts(configured(environment, "no_proxy"))
        options.extend((f"-Dhttp.nonProxyHosts={bypass}", f"-Dhttps.nonProxyHosts={bypass}"))
    return " ".join(options)


if __name__ == "__main__":
    try:
        routing_keys = ("http_proxy", "https_proxy", "all_proxy", "no_proxy",
                        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY")
        print(jvm_proxy_options({key: os.environ[key] for key in routing_keys if key in os.environ}))
    except ProxyConfigurationError as error:
        print(f"Java proxy configuration failed: {error}", file=sys.stderr)
        raise SystemExit(2)
