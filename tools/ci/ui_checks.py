"""UI readiness checks shared by the emulator harness and host regression tests."""
import time
import xml.etree.ElementTree as ET


def immersive_confirmation(root):
    known_intro = any(node.get("package") == "android" and
                      node.get("resource-id") == "android:id/immersive_cling_title"
                      for node in root.iter("node"))
    if known_intro:
        return next((node for node in root.iter("node") if node.get("package") == "android" and
                     node.get("resource-id") == "android:id/ok" and node.get("enabled") == "true"), None)
    return None


def pixel_launcher_anr_close(root):
    # This first-boot overlay can cover a healthy, streaming camera on the CI emulator.
    # A crash/ANR of HorizonCam (or any other process) must remain visible to the test.
    title = root.find(".//node[@resource-id='android:id/alertTitle'][@package='android']")
    if title is None or title.get("text") != "Pixel Launcher isn't responding":
        return None
    return root.find(".//node[@resource-id='android:id/aerr_close'][@package='android'][@enabled='true']")


def wait_for(predicate, message, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        # ElementTree leaf buttons have no children and therefore bool(element) is False.
        if isinstance(result, ET.Element) or result:
            return result
        time.sleep(0.5)
    raise AssertionError(message)
