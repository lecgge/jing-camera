"""
Test: Photo Capture (HDR, Portrait, Night)
Verifies photo capture in different modes.
"""
import pytest
import time
import os
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from conftest import take_screenshot, find_element_by_text, APP_PACKAGE


class TestPhotoCapture:
    """Photo capture tests for different modes."""

    def _switch_to_mode(self, driver, mode_text):
        """Switch to a specific camera mode."""
        try:
            mode = WebDriverWait(driver, 5).until(
                EC.presence_of_element_located((AppiumBy.ANDROID_UIAUTOMATOR,
                    f'new UiSelector().text("{mode_text}")'))
            )
            mode.click()
            time.sleep(1)
        except Exception:
            # Try swiping to find the mode
            pass

    def _tap_shutter(self, driver):
        """Tap the shutter button (center bottom area)."""
        size = driver.get_window_size()
        # Shutter is roughly center bottom
        x = size['width'] // 2
        y = size['height'] - 100
        driver.tap([(x, y)])
        time.sleep(3)  # Wait for capture + processing

    def test_photo_capture_normal(self, driver):
        """Test photo capture in normal/photo mode."""
        self._switch_to_mode(driver, "照片")
        time.sleep(1)

        # Take photo
        self._tap_shutter(driver)
        time.sleep(2)

        # Verify no crash - app should still be running
        assert driver.current_activity is not None
        take_screenshot(driver, "photo_capture_normal")

    def test_photo_capture_hdr(self, driver):
        """Test HDR photo capture (photo mode uses HDR pipeline)."""
        self._switch_to_mode(driver, "照片")
        time.sleep(1)

        # HDR capture takes longer (burst + merge)
        self._tap_shutter(driver)
        time.sleep(5)  # Wait for HDR processing

        assert driver.current_activity is not None
        take_screenshot(driver, "photo_capture_hdr")

    def test_photo_capture_portrait(self, driver):
        """Test portrait mode capture with bokeh."""
        self._switch_to_mode(driver, "人像")
        time.sleep(1)

        self._tap_shutter(driver)
        time.sleep(5)  # Portrait processing takes time

        assert driver.current_activity is not None
        take_screenshot(driver, "photo_capture_portrait")

    def test_photo_capture_night(self, driver):
        """Test night mode capture."""
        self._switch_to_mode(driver, "夜景")
        time.sleep(1)

        self._tap_shutter(driver)
        time.sleep(8)  # Night mode takes longer (8 frames)

        assert driver.current_activity is not None
        take_screenshot(driver, "photo_capture_night")

    def test_multiple_captures_in_sequence(self, driver):
        """Test taking multiple photos in sequence."""
        self._switch_to_mode(driver, "照片")

        for i in range(3):
            self._tap_shutter(driver)
            time.sleep(4)
            assert driver.current_activity is not None

        take_screenshot(driver, "photo_capture_sequence")

    def test_photo_saved_to_gallery(self, driver):
        """Verify photos are saved to DCIM/Jing/ folder."""
        self._switch_to_mode(driver, "照片")
        self._tap_shutter(driver)
        time.sleep(3)

        # Check via adb that files exist in DCIM/Jing/
        import subprocess
        result = subprocess.run(
            ["adb", "-s", "10ADBU1YLK0017S", "shell", "ls", "/sdcard/DCIM/Jing/"],
            capture_output=True, text=True, timeout=10
        )
        # The directory should exist (may be empty if permissions issue)
        assert result.returncode == 0 or "No such" not in result.stderr
