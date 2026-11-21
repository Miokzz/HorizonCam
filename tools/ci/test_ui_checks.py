import unittest
import xml.etree.ElementTree as ET

from ui_checks import wait_for


class UiReadinessTest(unittest.TestCase):
    def test_visible_xml_leaf_button_is_a_valid_successful_wait_result(self):
        node = ET.fromstring('<node resource-id="com.miokzz.horizoncam.gl:id/recordButton" enabled="true"/>')
        self.assertEqual(len(node), 0)
        self.assertIs(wait_for(lambda: node, "leaf button incorrectly considered missing", seconds=0.1), node)

    def test_normal_boolean_predicate_still_returns_success(self):
        self.assertIs(wait_for(lambda: True, "boolean wait failed", seconds=0.1), True)

    def test_unfulfilled_wait_still_fails(self):
        with self.assertRaisesRegex(AssertionError, "missing control"):
            wait_for(lambda: None, "missing control", seconds=0)


if __name__ == "__main__":
    unittest.main()
