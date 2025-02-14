/*
 * (C) Copyright 2011-2016 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Sun Seng David TAN
 *     Florent Guillaume
 *     Benoit Delbosc
 *     Antoine Taillefer
 *     Anahide Tchertchian
 *     Guillaume Renard
 *     Mathieu Guillaume
 *     Julien Carsique
 */
package org.nuxeo.functionaltests;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.functionaltests.Constants.ADMINISTRATOR;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.MethodRule;
import org.nuxeo.functionaltests.JavaScriptErrorCollector.JavaScriptErrorIgnoreRule;
import org.nuxeo.functionaltests.drivers.ChromeDriverProvider;
import org.nuxeo.functionaltests.drivers.FirefoxDriverProvider;
import org.nuxeo.functionaltests.drivers.RemoteFirefoxDriverProvider;
import org.nuxeo.functionaltests.fragment.WebFragment;
import org.nuxeo.functionaltests.pages.AbstractPage;
import org.nuxeo.functionaltests.pages.DocumentBasePage;
import org.nuxeo.functionaltests.pages.DocumentBasePage.UserNotConnectedException;
import org.nuxeo.functionaltests.pages.LoginPage;
import org.nuxeo.functionaltests.proxy.ProxyManager;
import org.nuxeo.runtime.api.Framework;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.NotFoundException;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.internal.WrapsElement;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.Command;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.DriverCommand;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;

import com.google.common.collect.ImmutableMap;

/**
 * Base functions for all pages.
 */
public abstract class AbstractTest {

    private static final Logger log = LogManager.getLogger(AbstractTest.class);

    /**
     * @since 5.9.2
     */
    public final static String TEST_USERNAME = "jdoe";

    /**
     * @since 5.9.2
     */
    public final static String TEST_PASSWORD = "test";

    /**
     * Polling frequency in milliseconds.
     *
     * @since 5.9.2
     */
    public static final int POLLING_FREQUENCY_MILLISECONDS = 100;

    public static final int POLLING_FREQUENCY_SECONDS = 1;

    /**
     * Page Load timeout in seconds.
     *
     * @since 5.9.2
     */
    public static final int PAGE_LOAD_TIME_OUT_SECONDS = 60;

    public static final int LOAD_TIMEOUT_SECONDS = 30;

    /**
     * Driver implicit wait in milliseconds.
     *
     * @since 8.3
     */
    public static final int IMPLICIT_WAIT_MILLISECONDS = 200;

    public static final int LOAD_SHORT_TIMEOUT_SECONDS = 2;

    public static final int AJAX_TIMEOUT_SECONDS = 10;

    public static final int AJAX_SHORT_TIMEOUT_SECONDS = 2;

    public static final String NUXEO_URL = System.getProperty("nuxeoURL", "http://localhost:8080/nuxeo")
                                                 .replaceAll("/$", "");

    public static RemoteWebDriver driver; // NOSONAR (public static but not final, ok for tests)

    protected static ProxyManager proxyManager;

    /**
     * Logger method to follow what's being run on server logs and take a screenshot of the last page in case of failure
     */
    @Rule
    public MethodRule watchman = new LogTestWatchman(driver, NUXEO_URL);

    /**
     * This method will be executed before any method registered with JUnit After annotation.
     *
     * @since 5.8
     */
    public void runBeforeAfters() {
        ((LogTestWatchman) watchman).runBeforeAfters();
    }

    @BeforeClass
    public static void initDriver() throws Exception {
        String browser = System.getProperty("browser", "firefox");
        // Use the same strings as command-line Selenium
        if (browser.equals("chrome") || browser.equals("firefox")) {
            initFirefoxDriver();
        } else if (browser.equals("remotefirefox")) {
            initRemoteFirefoxDriver();
        } else if (browser.equals("googlechrome")) {
            initChromeDriver();
        } else {
            throw new RuntimeException("Browser not supported: " + browser);
        }
        driver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIME_OUT_SECONDS, TimeUnit.SECONDS);
        driver.manage().timeouts().implicitlyWait(IMPLICIT_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    protected static void initFirefoxDriver() throws Exception {
        proxyManager = new ProxyManager();
        Proxy proxy = proxyManager.startProxy();
        if (proxy != null) {
            proxy.setNoProxy("");
        }
        DesiredCapabilities dc = DesiredCapabilities.firefox();
        dc.setCapability(CapabilityType.PROXY, proxy);
        driver = new FirefoxDriverProvider().init(dc);
    }

    protected static void initRemoteFirefoxDriver() throws Exception {
        proxyManager = new ProxyManager();
        Proxy proxy = proxyManager.startProxy();
        if (proxy != null) {
            proxy.setNoProxy("");
        }
        DesiredCapabilities dc = DesiredCapabilities.firefox();
        dc.setCapability(CapabilityType.PROXY, proxy);
        driver = new RemoteFirefoxDriverProvider().init(dc);
    }

    protected static void initChromeDriver() throws Exception {
        proxyManager = new ProxyManager();
        Proxy proxy = proxyManager.startProxy();
        DesiredCapabilities dc = DesiredCapabilities.chrome();
        if (proxy != null) {
            proxy.setNoProxy("");
            dc.setCapability(CapabilityType.PROXY, proxy);
        }
        driver = new ChromeDriverProvider().init(dc);
    }

    /**
     * @since 9.3
     */
    protected JavaScriptErrorIgnoreRule[] ignores = new JavaScriptErrorIgnoreRule[0];

    /**
     * @since 9.3
     */
    public void addAfterTestIgnores(JavaScriptErrorIgnoreRule... ignores) {
        this.ignores = ArrayUtils.addAll(this.ignores, ignores);
    }

    /**
     * @since 7.1
     */
    @After
    public void checkJavascriptError() {
        JavaScriptErrorCollector.from(driver).ignore(ignores).checkForErrors();
    }

    @AfterClass
    public static void quitDriver() {
        if (driver != null) {
            driver.quit();
            driver = null;
        }

        try {
            proxyManager.stopProxy();
            proxyManager = null;
        } catch (Exception e) {
            log.error("Could not stop proxy: {}", e.getMessage());
        }
    }

    public static <T> T get(String url, Class<T> pageClassToProxy, JavaScriptErrorIgnoreRule... ignores) {
        JavaScriptErrorCollector.from(driver).ignore(ignores).checkForErrors();
        driver.get(url);
        return asPage(pageClassToProxy);
    }

    /**
     * Opens given url adding hardcoded Seam conversation named "0NXMAIN".
     *
     * @since 8.3
     */
    public static void open(String url, JavaScriptErrorIgnoreRule... ignores) {
        JavaScriptErrorCollector.from(driver).ignore(ignores).checkForErrors();
        driver.get(NUXEO_URL + url + "?conversationId=0NXMAIN");
    }

    /**
     * Do not wait for page load. Do not handle error. Do not give explicit error in case of failure. This is a very raw
     * get.
     *
     * @since 6.0
     */
    public static <T> T getWithoutErrorHandler(String url, Class<T> pageClassToProxy) throws IOException {
        Command command = new Command(AbstractTest.driver.getSessionId(), DriverCommand.GET,
                ImmutableMap.of("url", url));
        AbstractTest.driver.getCommandExecutor().execute(command);
        return asPage(pageClassToProxy);
    }

    public static WebDriver getPopup() {
        return switchToPopup(null);
    }

    /**
     * Focus popup that contains text parameter value in his current URL.
     *
     * @param text that must be contained in popup URL
     * @return WebDriver instance to be chained
     * @since 9.3
     */
    public static WebDriver switchToPopup(String text) {
        String currentWindow = null;
        try {
            currentWindow = driver.getWindowHandle();
        } catch (NoSuchWindowException ignored) {
            // Nothing to do; it can happen when manipulating closed popups
        }

        for (String popup : driver.getWindowHandles()) {
            if (popup.equals(currentWindow)) {
                continue;
            }

            driver.switchTo().window(popup);
            if (text == null || driver.getCurrentUrl().contains(text)) {
                return driver;
            }
        }

        throw new NotFoundException("Popup not found: " + text);
    }

    public static <T> T asPage(Class<T> pageClassToProxy) {
        T page = instantiatePage(pageClassToProxy);
        return fillElement(pageClassToProxy, page);
    }

    public static <T extends WebFragment> T getWebFragment(By by, Class<T> webFragmentClass) {
        WebElement element = Locator.findElementWithTimeout(by);
        return getWebFragment(element, webFragmentClass);
    }

    public static <T extends WebFragment> T getWebFragment(WebElement element, Class<T> webFragmentClass) {
        T webFragment = instantiateWebFragment(element, webFragmentClass);
        webFragment = fillElement(webFragmentClass, webFragment);
        // fillElement somehow overwrite the 'element' field, reset it.
        webFragment.setElement(element);
        return webFragment;
    }

    /**
     * Fills an instantiated page/form/widget attributes
     *
     * @since 5.7
     */
    public static <T> T fillElement(Class<T> pageClassToProxy, T page) {
        PageFactory.initElements(new VariableElementLocatorFactory(driver, AJAX_TIMEOUT_SECONDS), page);
        // check all required WebElements on the page and wait for their
        // loading
        final List<String> fieldNames = new ArrayList<>();
        final List<WrapsElement> elements = new ArrayList<>();
        for (Field field : pageClassToProxy.getDeclaredFields()) {
            if (field.getAnnotation(Required.class) != null) {
                try {
                    field.setAccessible(true);
                    fieldNames.add(field.getName());
                    elements.add((WrapsElement) field.get(page));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        Wait<T> wait = new FluentWait<>(page).withTimeout(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                             .pollingEvery(POLLING_FREQUENCY_MILLISECONDS, TimeUnit.MILLISECONDS);
        T res;
        try {
            res = wait.until(aPage -> {
                String notLoaded = anyElementNotLoaded(elements, fieldNames);
                if (notLoaded == null) {
                    return aPage;
                } else {
                    return null;
                }
            });
        } catch (TimeoutException e) {
            throw new TimeoutException("not loaded: " + anyElementNotLoaded(elements, fieldNames), e);
        }
        // check if there are JQuery ajax requests to complete
        if (pageClassToProxy.isAnnotationPresent(WaitForJQueryAjaxOnLoading.class)) {
            new AjaxRequestManager(driver).waitForJQueryRequests();
        }
        return res;
    }

    protected static String anyElementNotLoaded(List<WrapsElement> proxies, List<String> fieldNames) {
        for (int i = 0; i < proxies.size(); i++) {
            WrapsElement proxy = proxies.get(i);
            try {
                // method implemented in LocatingElementHandler
                proxy.getWrappedElement();
            } catch (NoSuchElementException e) {
                return fieldNames.get(i);
            }
        }
        return null;
    }

    // private in PageFactory...
    protected static <T> T instantiatePage(Class<T> pageClassToProxy) {
        try {
            try {
                Constructor<T> constructor = pageClassToProxy.getConstructor(WebDriver.class);
                return constructor.newInstance(driver);
            } catch (NoSuchMethodException e) {
                return pageClassToProxy.getDeclaredConstructor().newInstance();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected static <T extends WebFragment> T instantiateWebFragment(WebElement element, Class<T> webFragmentClass) {
        try {
            try {
                Constructor<T> constructor = webFragmentClass.getConstructor(WebDriver.class, WebElement.class);
                return constructor.newInstance(driver, element);
            } catch (NoSuchMethodException e) {
                return webFragmentClass.getDeclaredConstructor().newInstance();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public LoginPage getLoginPage() {
        return get(NUXEO_URL + "/logout", LoginPage.class);
    }

    public LoginPage logout() {
        JavaScriptErrorCollector.from(driver).ignore(ignores).checkForErrors();
        return getLoginPage();
    }

    /**
     * Logs out without expecting to be redirected to the login page. This can be the case on a simple server
     * distribution when logged in: the logout action can redirect to the home.html startup page.
     *
     * @since 9.2
     */
    public void logoutSimply() {
        driver.get(NUXEO_URL + "/logout");
    }

    /**
     * navigate to a link text. wait until the link is available and click on it.
     */
    public <T extends AbstractPage> T nav(Class<T> pageClass, String linkText) {
        WebElement link = Locator.findElementWithTimeout(By.linkText(linkText));
        if (link == null) {
            return null;
        }
        link.click();
        return asPage(pageClass);
    }

    /**
     * Navigate to a specified url
     *
     * @param urlString url
     */
    public void navToUrl(String urlString) throws MalformedURLException {
        URL url = new URL(urlString);
        driver.navigate().to(url);
    }

    /**
     * Login as Administrator
     *
     * @return the Document base page (by default returned by CAP)
     */
    public DocumentBasePage login() throws UserNotConnectedException {
        return login(ADMINISTRATOR, ADMINISTRATOR);
    }

    public DocumentBasePage login(String username, String password) throws UserNotConnectedException {
        DocumentBasePage documentBasePage = getLoginPage().login(username, password, DocumentBasePage.class);
        documentBasePage.checkUserConnected(username);
        return documentBasePage;
    }

    /**
     * Login as default test user.
     *
     * @since 5.9.2
     */
    public DocumentBasePage loginAsTestUser() throws UserNotConnectedException {
        return login(TEST_USERNAME, TEST_PASSWORD);
    }

    /**
     * Login using an invalid credential.
     *
     * @param username the username
     * @param password the password
     */
    public LoginPage loginInvalid(String username, String password) {
        return getLoginPage().login(username, password, LoginPage.class);
    }

    /**
     * Creates a temporary file and returns its absolute path.
     *
     * @param filePrefix the file prefix
     * @param fileSuffix the file suffix
     * @param fileContent the file content
     * @return the temporary file to upload path
     * @throws IOException if temporary file creation fails
     * @since 5.9.3
     */
    public static String getTmpFileToUploadPath(String filePrefix, String fileSuffix, String fileContent)
            throws IOException {
        // Create tmp file, deleted on exit
        File tmpFile = Framework.createTempFile(filePrefix, fileSuffix);
        tmpFile.deleteOnExit();
        FileUtils.writeStringToFile(tmpFile, fileContent, UTF_8);
        assertTrue(tmpFile.exists());

        // Check file URI protocol
        assertEquals("file", tmpFile.toURI().toURL().getProtocol());

        // Return file absolute path
        return tmpFile.getAbsolutePath();
    }

    /**
     * Get the current document id stored in the javascript ctx.currentDocument variable of the current page.
     *
     * @return the current document id
     * @since 5.7
     */
    protected String getCurrentDocumentId() {
        return (String) driver.executeScript("return ctx.currentDocument;");
    }
}
