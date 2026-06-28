#!/usr/bin/env python3
"""
Verify that your PostHog project receives `$exception` events in Error Tracking.

This is a one-off developer tool — it sends a single synthetic exception straight
to PostHog via the official Python SDK (the same `$exception` event shape the
Android app's `PostHog.captureException` produces), so you can confirm your
project's Error Tracking is enabled and ingesting, *without* needing the app or
a device. Run it after enabling Error Tracking in Project Settings.

Usage:
    pip install posthog
    python scripts/verify_posthog_exceptions.py

Then check PostHog -> Error Tracking (and Activity) for the test exception,
tagged `test=true, source=python-verification-script` so it can be filtered out
or deleted afterwards.
"""

import sys

try:
    from posthog import Posthog
except ImportError:
    sys.exit("posthog not installed. Run: pip install posthog")

# Same project + host the Android app uses (AnalyticsService.kt).
POSTHOG_KEY = "phc_ubtNRuCT7Zqo4dVrVWRnJRYE9m9WqGeTyK7zVDKQ968"
POSTHOG_HOST = "https://eu.i.posthog.com"


def main() -> int:
    print(f"PostHog host : {POSTHOG_HOST}")
    print(f"Project key  : {POSTHOG_KEY[:12]}...{POSTHOG_KEY[-4:]}")
    print("This will send ONE synthetic exception to your PostHog project.")
    if input("Proceed? [y/N] ").strip().lower() not in ("y", "yes"):
        print("Aborted.")
        return 1

    client = Posthog(project_api_key=POSTHOG_KEY, host=POSTHOG_HOST)
    try:
        try:
            raise RuntimeError("Dasher verification: synthetic crash from Python script")
        except Exception as exc:
            event_id = client.capture_exception(
                exc,
                distinct_id="posthog-verification-script",
                properties={
                    "source": "python-verification-script",
                    "test": True,
                },
            )
        client.flush()
        print(f"\nSent. event id: {event_id}")
        print("Now check:")
        print("  1. PostHog -> Activity           (the $exception event should appear)")
        print("  2. PostHog -> Error Tracking      (the exception should land here")
        print("                                       if Error Tracking is enabled in")
        print("                                       Project Settings)")
        print("Tagged test=true so you can filter/delete it.")
        return 0
    finally:
        client.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
