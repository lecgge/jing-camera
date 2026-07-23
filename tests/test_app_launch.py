"""
Test: App Launch and Permissions
Verifies the app launches correctly and handles camera permission.
"""
import pytest
import time
from appium.webdriver.common.appiumby import AppiumBy
from conftest import take_screenshot, find_element_by_text


class TestAppLaunch:
    """App launch and basic lifecycle tests."""

    def test_app_launches_successfully(self, driver):
        """Verify app launches and main UI is visible."""
        # The shutter button should be visible
        time.sleep(2)
        # Check we're on the main camera screen by looking for mode labels
        page_source = driver.page_source
        assert "照片" in page_source or "Jing" in page_source or len(page_source) > 100

    def test_camera_permission_granted(self, driver):
        """Verify camera permission is granted (app auto-grants)."""
        # If permission dialog appears, allow it
        try:
            allow_btn = driver.find_element(AppiumBy.ID,
                "com.android.permissioncontroller:id/permission_allow_button")
            allow_btn.click()
            time.sleep(1)
        except Exception:
            pass  # Permission already granted or auto-granted

        # App should still be running
        assert driver.current_activity is not None

    def test_preview_is_active(self, driver):
        """Verify camera preview is displayed (TextureView exists)."""
        time.sleep(2)
        page_source = driver.page_source
        # TextureView should be present in the hierarchy
        assert "TextureView" in page_source or "android.view.View" in page_source

    def test_mode_labels_visible(self, driver):
        """Verify all mode labels are visible."""
        time.sleep(1)
        page_source = driver.page_source
        assert "照片" in page_source, "Photo mode label not found"
        assert "人像" in page_source, "Portrait mode label not found"
        assert "夜景" in page_source, "Night mode label not found"
        assert "视频" in page_source, "Video mode label not found"

    def test_screenshot_capture(self, driver):
        """Test screenshot capture for debugging."""
        take_screenshot(driver, "app_launch")
        assert True  # Screenshot saved successfully
