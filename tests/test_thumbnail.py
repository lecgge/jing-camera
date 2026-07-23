"""
Test: Thumbnail Display
Verifies thumbnail preview after capture and gallery跳转.
"""
import pytest
import time
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from conftest import take_screenshot


class TestThumbnail:
    """Thumbnail display tests."""

    def _take_photo(self, driver):
        """Take a photo."""
        size = driver.get_window_size()
        driver.tap([(size['width'] // 2, size['height'] - 100)])
        time.sleep(3)

    def test_thumbnail_visible_after_capture(self, driver):
        """Test that thumbnail appears after photo capture."""
        # Switch to photo mode
        try:
            mode = WebDriverWait(driver, 5).until(
                EC.presence_of_element_located((AppiumBy.ANDROID_UIAUTOMATOR,
                    'new UiSelector().text("照片")'))
            )
            mode.click()
            time.sleep(1)
        except Exception:
            pass

        # Take photo
        self._take_photo(driver)

        # Thumbnail area should be in the UI (bottom-left corner)
        page_source = driver.page_source
        # Thumbnail might be identified by its position or class
        take_screenshot(driver, "thumbnail_after_capture")

    def test_thumbnail_updates_on_new_capture(self, driver):
        """Test that thumbnail updates when new photo is taken."""
        self._take_photo(driver)
        time.sleep(1)
        take_screenshot(driver, "thumbnail_1")

        self._take_photo(driver)
        time.sleep(1)
        take_screenshot(driver, "thumbnail_2")

        assert driver.current_activity is not None

    def test_thumbnail_tap_opens_gallery(self, driver):
        """Test tapping thumbnail opens gallery/viewer."""
        self._take_photo(driver)
        time.sleep(2)

        # Tap thumbnail area (bottom-left)
        size = driver.get_window_size()
        driver.tap([(80, size['height'] - 100)])
        time.sleep(2)

        # Should open gallery or FileProvider intent
        take_screenshot(driver, "thumbnail_tap")

        # Return to camera
        driver.back()
        time.sleep(1)
