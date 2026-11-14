#!/usr/bin/env python3
"""Print boolean GoTrue settings only. Never print URLs, keys, or allow-lists."""
import json
import sys


def flag(value):
    if value is True:
        return "true"
    if value is False:
        return "false"
    return "unknown"


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/supabase-auth-settings.json"
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except Exception:
        print("auth_settings_parsed=false")
        return 0
    external = data.get("external") if isinstance(data.get("external"), dict) else {}
    print("signup_disabled=" + flag(data.get("disable_signup")))
    print("mailer_autoconfirm=" + flag(data.get("mailer_autoconfirm")))
    print("provider_email=" + flag(external.get("email")))
    print("provider_google=" + flag(external.get("google")))
    print("auth_settings_parsed=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
