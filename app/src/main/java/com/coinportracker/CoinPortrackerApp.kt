package com.coinportracker

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.coinportracker.data.AppContainer
import com.coinportracker.data.Notifier
import com.coinportracker.data.PriceRefreshWorker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CoinPortrackerApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.ensureChannel(this)
        com.coinportracker.webhost.WebHostService.ensureChannel(this)
        container.scope.launch {
            container.settings.settings.map { it.backgroundRefreshMin }.distinctUntilChanged().collect { PriceRefreshWorker.schedule(this@CoinPortrackerApp, it) }
        }
    }

    /** Coil: GIF animado via ImageDecoder (API 28+, hardware) com fallback GifDecoder; sem cache em disco de imagens remotas (só arquivos locais). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
