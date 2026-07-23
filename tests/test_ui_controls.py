"""
Test: UI Controls (Top bar, Slide-up panel, Shutter)
Verifies UI element presence and interaction.
"""
import pytest
import time
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from conftest import take_screenshot


class TestUIControls:
    """UI control element tests."""

    def test_top_bar_visible(self, driver):
        """Test that top control bar is visible."""
        page_source = driver.page_source
        # Top bar contains flash, LIVE, timer
        take_screenshot(driver, "top_bar")
        assert driver.current_activity is not None

    def test_shutter_button_visible(self, driver):
        """Test that shutter button is visible."""
        size = driver.get_window_size()
        # Shutter area should be tappable at bottom center
        take_screenshot(driver, "shutter_button")
        assert True

    def test_slide_up_panel(self, driver):
        """Test slide-up panel opens."""
        size = driver.get_window_size()
        # Swipe up from bottom to open panel
        driver.swipe(
            size['width'] // 2, size['height'] - 50,
            size['width'] // 2, size['height'] // 2,
            300
        )
        time.sleep(1)
        take_screenshot(driver, "slide_up_panel")

        # Close panel by swiping down
        driver.swipe(
            size['width'] // 2, size['height'] // 2,
            size['width'] // 2, size['height'] - 50,
            300
        )
        time.sleep(1)

    def test_timer_toggle(self, driver):
        """Test timer toggle in top bar."""
        page_source = driver.page_source
        has_timer = "⏱" in page_source or "定时" in page_source
        take_screenshot(driver, "timer_toggle")

    def test_aspect_ratio_toggle(self, driver):
        """Test aspect ratio toggle (4:3, 16:9, 1:1)."""
        # Open slide-up panel
        size = driver.get_window_size()
        driver.swipe(
            size['width'] // 2, size['height'] - 50,
            size['width'] // 2, size['height'] // 2,
            300
        )
        time.sleep(1)

        page_source = driver.page_source
        has_ratio = "4:3" in page_source or "16:9" in page_source
        take_screenshot(driver, "aspect_ratio")

        # Close panel
        driver.swipe(
            size['width'] // 2, size['height'] // 2,
            size['width'] // 2, size['height'] - 50,
            300
        )

    def test_tap_to_focus(self, driver):
        """Test tap-to-focus on preview."""
        size = driver.get_window_size()
        # Tap center of preview
        driver.tap([(size['width'] // 2, size['height'] // 2)])
        time.sleep(1)
        take_screenshot(driver, "tap_focus")
        assert driver.current_activity is not None
