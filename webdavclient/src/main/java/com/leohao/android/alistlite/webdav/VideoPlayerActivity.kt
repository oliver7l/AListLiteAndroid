package com.leohao.android.alistlite.webdav

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class VideoPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val videoUrl = intent.getStringExtra("video_url") ?: ""
        val videoTitle = intent.getStringExtra("video_title") ?: "播放"
        val username = intent.getStringExtra("username") ?: ""
        val password = intent.getStringExtra("password") ?: ""

        playerView = findViewById(R.id.player_view)
        progressBar = findViewById(R.id.progress_bar)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = videoTitle
        toolbar.setNavigationOnClickListener { finish() }

        setupPlayer(videoUrl, username, password)
    }

    private fun setupPlayer(url: String, username: String, password: String) {
        // 创建带 Basic Auth 的 OkHttpClient
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = if (username.isNotEmpty()) {
                    chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(username, password))
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            })
            .build()

        // 使用 OkHttpDataSource 使 ExoPlayer 通过 OkHttp 加载视频
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .build()

        player = ExoPlayer.Builder(this)
            .build()
            .apply {
                setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem))
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> progressBar.visibility = View.VISIBLE
                            Player.STATE_READY -> progressBar.visibility = View.GONE
                            else -> {}
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) progressBar.visibility = View.GONE
                    }
                })

                prepare()
            }

        playerView.player = player
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
