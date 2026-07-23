"""
Appium test configuration and fixtures for "Jing" camera app.
"""
import os
import pytest
import time
import subprocess
from appium import webdriver
from appium.options.android import UiAutomator2Options

# Set Android SDK path
os.environ["ANDROID_HOME"] = r"D:\Program Files\Android\SDK"
os.environ["ANDROID_SDK_ROOT"] = r"D:\Program Files\Android\SDK"


# ─── Device Configuration ───────────────────────────────────────────────────
DEVICE_UDID = "10ADBU1YLK0017S"  # vivo X100 Pro
APPIUM_SERVER = "http://127.0.0.1:4723"
APP_PACKAGE = "com.jing.camera"
APP_ACTIVITY = "com.jing.camera.MainActivity"


def create_driver():
    """Create and return an Appium driver instance."""
    options = UiAutomator2Options()
    options.platform_name = "Android"
    options.device_name = "vivo X100 Pro"
    options.udid = DEVICE_UDID
    options.app_package = APP_PACKAGE
    options.app_activity = APP_ACTIVITY
    options.automation_name = "UiAutomator2"
    options.no_reset = False
    options.full_reset = False
    options.new_command_timeout = 300
    options.auto_grant_permissions = True
    options.dont_stop_app_on_reset = False

    driver = webdriver.Remote(APPIUM_SERVER, options=options)
    driver.implicitly_wait(10)
    return driver


@pytest.fixture(scope="session")
def driver():
    """
    Session-scoped Appium driver fixture.
    Creates one driver for the entire test session.
    """
    d = create_driver()
    time.sleep(3)  # Wait for app to fully load
    yield d
    d.quit()


@pytest.fixture(scope="function")
def fresh_driver():
    """
    Function-scoped Appium driver fixture.
    Creates a new driver for each test (slower but isolated).
    """
    d = create_driver()
    time.sleep(3)
    yield d
    d.quit()


@pytest.fixture(scope="function")
def restart_app(driver):
    """Restart the app before each test."""
    driver.terminate_app(APP_PACKAGE)
    time.sleep(1)
    driver.activate_app(APP_PACKAGE)
    time.sleep(2)
    return driver


# ─── Helper Functions ───────────────────────────────────────────────────────

def find_element_by_id(driver, resource_id, timeout=10):
    """Find element by resource ID."""
    from appium.webdriver.common.appiumby import AppiumBy
    from selenium.webdriver.support.ui import WebDriverWait
    from selenium.webdriver.support import expected_conditions as EC

    return WebDriverWait(driver, timeout).until(
        EC.presence_of_element_located((AppiumBy.ID, f"{APP_PACKAGE}:id/{resource_id}"))
    )


def find_element_by_text(driver, text, timeout=10):
    """Find element by text content."""
    from appium.webdriver.common.appiumby import AppiumBy
    from selenium.webdriver.support.ui import WebDriverWait
    from selenium.webdriver.support import expected_conditions as EC

    return WebDriverWait(driver, timeout).until(
        EC.presence_of_element_located((AppiumBy.ANDROID_UIAUTOMATOR,
            f'new UiSelector().text("{text}")'))
    )


def find_element_by_desc(driver, desc, timeout=10):
    """Find element by content description."""
    from appium.webdriver.common.appiumby import AppiumBy
    from selenium.webdriver.support.ui import WebDriverWait
    from selenium.webdriver.support import expected_conditions as EC

    return WebDriverWait(driver, timeout).until(
        EC.presence_of_element_located((AppiumBy.ACCESSIBILITY_ID, desc))
    )


def take_screenshot(driver, name):
    """Take a screenshot and save it."""
    import os
    screenshot_dir = os.path.join(os.path.dirname(__file__), "screenshots")
    os.makedirs(screenshot_dir, exist_ok=True)
    path = os.path.join(screenshot_dir, f"{name}.png")
    driver.save_screenshot(path)
    return path


def wait_for_element_gone(driver, locator, timeout=10):
    """Wait until an element disappears."""
    from selenium.webdriver.support.ui import WebDriverWait
    from selenium.webdriver.support import expected_conditions as EC

    try:
        WebDriverWait(driver, timeout).until(
            EC.invisibility_of_element_located(locator)
        )
        return True
    except Exception:
        return False
