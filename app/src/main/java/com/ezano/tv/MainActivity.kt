package com.ezano.tv

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ezano.tv.data.Net
import com.ezano.tv.data.Repo
import com.ezano.tv.ui.AppRoot
import com.ezano.tv.ui.theme.EzanoTheme

class EzanoApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }

    /** Shared image loader: big memory + disk cache so posters/logos appear instantly. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Net.client }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Repo.store.activePlaylist != null && Repo.index.value == null) Repo.loadActive()
        setContent {
            EzanoTheme {
                AppRoot(onExit = { finish() })
            }
        }
    }
}
