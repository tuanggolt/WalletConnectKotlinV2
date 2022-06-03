package com.walletconnect.walletconnectv2

import io.appium.java_client.android.AndroidDriver
import org.junit.After
import org.junit.Before
import org.openqa.selenium.remote.DesiredCapabilities
import java.net.URL
import java.time.Duration


internal open class BaseAppiumTest {
    protected lateinit var nativeDriver: AndroidDriver
    protected lateinit var chromeDriver: AndroidDriver

    @Before
    fun setup() {
        val browserCapabilities = DesiredCapabilities()
        browserCapabilities.setCapability("browserName", "Chrome")
        chromeDriver = AndroidDriver(URL("http://10.0.2.2:4723/wd/hub"), browserCapabilities)
        chromeDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10L))


        val nativeCapabilities = DesiredCapabilities()
        nativeDriver = AndroidDriver(URL("http://10.0.2.2:4723/wd/hub"), nativeCapabilities)
        nativeDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10L))
    }

    @After
    fun tearDown() {
        nativeDriver.quit()
        chromeDriver.quit()
    }

    object Native {
        object Dapp {
            const val appPackage = "com.walletconnect.dapp"
            const val appActivity = ".ui.host.DappSampleActivity"
        }

        object Wallet {
            const val appPackage = "com.walletconnect.wallet"
            const val id = "$appPackage:id/"
            const val appActivity = ".ui.host.WalletSampleActivity"
        }
    }

    object Browser {
        const val appPackage = "com.android.chrome"

        object Dapp {
            const val url = "https://react-dapp-beta.walletconnect.com/"
        }

        object Wallet {
            const val url = "https://react-wallet-beta.walletconnect.com/"
        }
    }
}