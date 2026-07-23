"""
Test: Mode Switching
Verifies camera mode switching (Photo/Portrait/Night/Video).
"""
import pytest
import time
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from conftest import take_screenshot


class TestModeSwitch:
    """Mode switching tests."""

    def _tap_mode(self, driver, mode_text):
        """Tap a mode label to switch to it."""
        try:
            mode = WebDriverWait(driver, 5).until(
                EC.presence_of_element_located((AppiumBy.ANDROID_UIAUTOMATOR,
                    f'new UiSelector().text("{mode_text}")'))
            )
            mode.click()
            time.sleep(1)
            return True
        except Exception:
            return False

    def test_switch_to_photo_mode(self, driver):
        """Test switching to photo mode."""
        assert self._tap_mode(driver, "照片")
        take_screenshot(driver, "mode_photo")

    def test_switch_to_portrait_mode(self, driver):
        """Test switching to portrait mode."""
        assert self._tap_mode(driver, "人像")
        take_screenshot(driver, "mode_portrait")

    def test_switch_to_night_mode(self, driver):
        """Test switching to night mode."""
        assert self._tap_mode(driver, "夜景")
        take_screenshot(driver, "mode_night")

    def test_switch_to_video_mode(self, driver):
        """Test switching to video mode."""
        assert self._tap_mode(driver, "视频")
        take_screenshot(driver, "mode_video")

    def test_swipe_mode_carousel(self, driver):
        """Test swiping the mode carousel."""
        size = driver.get_window_size()
        y = size['height'] - 200  # Mode carousel Y position

        # Swipe left (next mode)
        driver.swipe(size['width'] * 0.8, y, size['width'] * 0.2, y, 300)
        time.sleep(1)
        take_screenshot(driver, "mode_swipe_left")

        # Swipe right (previous mode)
        driver.swipe(size['width'] * 0.2, y, size['width'] * 0.8, y, 300)
        time.sleep(1)
        take_screenshot(driver, "mode_swipe_right")

    def test_mode_switch_cycle_all(self, driver):
        """Test cycling through all modes."""
        modes = ["照片", "人像", "夜景", "视频"]
        for mode in modes:
            self._tap_mode(driver, mode)
            time.sleep(0.5)
            assert driver.current_activity is not None

        take_screenshot(driver, "mode_cycle_all")
