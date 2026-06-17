package com.iptv.fourj

import android.app.Application
import com.iptv.fourj.data.db.ProviderStore
import com.iptv.fourj.data.repository.IptvRepository

class FourJApp : Application() {
    val providerStore by lazy { ProviderStore(this) }
    val repository by lazy { IptvRepository(providerStore, this) }
}
