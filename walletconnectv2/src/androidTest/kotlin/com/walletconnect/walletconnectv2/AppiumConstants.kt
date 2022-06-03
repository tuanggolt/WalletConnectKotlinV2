package com.walletconnect.walletconnectv2

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
