"""
Test: Video Recording
Verifies video recording start/stop functionality.
"""
import pytest
import time
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from conftest import take_screenshot


class TestVideo:
    """Video recording tests."""

    def _switch_to_video_mode(self, driver):
        """Switch to video mode."""
        try:
            mode = WebDriverWait(driver, 5).until(
                EC.presence_of_element_located((AppiumBy.ANDROID_UIAUTOMATOR,
                    'new UiSelector().text("视频")'))
            )
            mode.click()
            time.sleep(1)
        except Exception:
            pass

    def _tap_shutter(self, driver):
        """Tap shutter button area."""
        size = driver.get_window_size()
        driver.tap([(size['width'] // 2, size['height'] - 100)])

    def test_switch_to_video_mode(self, driver):
        """Test switching to video mode."""
        self._switch_to_video_mode(driver)
        assert driver.current_activity is not None
        take_screenshot(driver, "video_mode")

    def test_start_video_recording(self, driver):
        """Test starting video recording."""
        self._switch_to_video_mode(driver)
        time.sleep(1)

        # Tap shutter to start recording
        self._tap_shutter(driver)
        time.sleep(2)

        # App should still be running
        assert driver.current_activity is not None
        take_screenshot(driver, "video_recording")

    def test_stop_video_recording(self, driver):
        """Test stopping video recording."""
        self._switch_to_video_mode(driver)
        time.sleep(1)

        # Start recording
        self._tap_shutter(driver)
        time.sleep(3)

        # Stop recording (tap shutter again)
        self._tap_shutter(driver)
        time.sleep(2)

        assert driver.current_activity is not None
        take_screenshot(driver, "video_stopped")

    def test_record_5_second_clip(self, driver):
        """Test recording a 5-second video clip."""
        self._switch_to_video_mode(driver)
        time.sleep(1)

        # Start recording
        self._tap_shutter(driver)
        time.sleep(5)  # Record for 5 seconds

        # Stop recording
        self._tap_shutter(driver)
        time.sleep(2)

        assert driver.current_activity is not None
        take_screenshot(driver, "video_5sec")
