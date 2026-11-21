"""UI readiness checks shared by the emulator harness and host regression tests."""
import time
import xml.etree.ElementTree as ET


def wait_for(predicate, message, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        # ElementTree leaf buttons have no children and therefore bool(element) is False.
        if isinstance(result, ET.Element) or result:
            return result
        time.sleep(0.5)
    raise AssertionError(message)
