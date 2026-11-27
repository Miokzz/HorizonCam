import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image
from offdevice_diffusion import centered
from offdevice_text_gate import chart, edit_distance, normalize


class TestPerceptualControls(unittest.TestCase):
    def test_ocr_character_substitutions_and_missing_characters(self):
        self.assertEqual(normalize("a17  B42\nC83"), "A17 B42 C83")
        for observed, distance in (("A17",0), ("AI7",1), ("17",1), ("AA17",1), ("",3)):
            self.assertEqual(edit_distance("A17",observed), distance)

    def test_chart_reference_immutable_and_crop_geometry(self):
        with TemporaryDirectory() as store:
            file = Path(store) / "chart.png"
            chart(file)
            with self.assertRaises(FileExistsError):
                chart(file)
            with Image.open(file) as im:
                self.assertEqual(im.size,(256,256))
                roi, rect = centered(im,64,1.,0.)
                self.assertEqual(rect,[192,0,64,64])
                self.assertEqual(roi.size,(64,64))
                with self.assertRaises(ValueError):
                    centered(im,300,0.,0.)
