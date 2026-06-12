package com.leohao.android.alistlite.webdav

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var tvPath: TextView
    private lateinit var layoutEmpty: View
    private lateinit var tvEmptyHint: TextView
    private lateinit var progressBar: View

    private val webdavClient = WebDAVClient()
    private var currentPath = "/"
    private var navigationStack = mutableListOf<String>()
    private var isConnected = false
    private var isLoading = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        initViews()
        setupToolbar()
        showConnectDialog()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recycler_files)
        tvPath = findViewById(R.id.tv_path)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        layoutEmpty = findViewById(R.id.layout_empty)
        tvEmptyHint = findViewById(R.id.tv_empty_hint)
        progressBar = findViewById(R.id.progress_bar)

        adapter = FileAdapter { resource ->
            if (resource.isDirectory) {
                navigateInto(resource.path)
            } else if (resource.isVideo) {
                playVideo(resource)
            } else {
                Toast.makeText(this, "不支持打开此文件类型", Toast.LENGTH_SHORT).show()
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener { refreshCurrentPath() }

        findViewById<View>(R.id.btn_connect).setOnClickListener { showConnectDialog() }

        layoutEmpty.setOnClickListener { showConnectDialog() }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            if (navigationStack.isNotEmpty()) {
                navigateBack()
            } else {
                finish()
            }
        }
    }

    private fun showConnectDialog() {
        val config = ServerConfig(
            name = "AList 本地服务",
            url = "http://127.0.0.1:5244/dav",
            username = "",
            password = ""
        )

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_connect, null)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_url)
        val etUsername = dialogView.findViewById<EditText>(R.id.et_username)
        val etPassword = dialogView.findViewById<EditText>(R.id.et_password)

        etUrl.setText(config.url)

        AlertDialog.Builder(this)
            .setTitle("连接 WebDAV 服务器")
            .setView(dialogView)
            .setPositiveButton("连接") { _, _ ->
                val url = etUrl.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newConfig = ServerConfig(
                    name = "WebDAV",
                    url = url,
                    username = etUsername.text.toString().trim(),
                    password = etPassword.text.toString().trim()
                )
                connectToServer(newConfig)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun connectToServer(config: ServerConfig) {
        showLoading(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    webdavClient.connect(config)
                } catch (e: Exception) {
                    false
                }
            }
            showLoading(false)
            if (result) {
                isConnected = true
                currentPath = "/"
                navigationStack.clear()
                tvEmptyHint.text = "正在加载..."
                Snackbar.make(recyclerView, "连接成功", Snackbar.LENGTH_SHORT).show()
                loadFiles("/")
            } else {
                isConnected = false
                tvEmptyHint.text = "连接失败，点击重试"
                layoutEmpty.visibility = View.VISIBLE
                Snackbar.make(recyclerView, "连接失败，请检查地址", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun loadFiles(path: String) {
        if (!isConnected) return
        showLoading(true)
        currentPath = path
        tvPath.text = path.ifEmpty { "/" }

        scope.launch {
            val files = withContext(Dispatchers.IO) {
                try {
                    webdavClient.listFiles(path)
                } catch (e: WebDAVException.AuthenticationFailed) {
                    mainHandler.post {
                        Snackbar.make(recyclerView, "认证失败，请检查用户名密码", Snackbar.LENGTH_LONG).show()
                    }
                    null
                } catch (e: Exception) {
                    mainHandler.post {
                        Snackbar.make(recyclerView, "加载失败: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
                    null
                }
            }

            showLoading(false)
            swipeRefresh.isRefreshing = false

            if (files != null) {
                adapter.submitList(files)
                layoutEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                if (files.isEmpty()) {
                    tvEmptyHint.text = "此目录为空"
                }
            }
        }
    }

    private fun navigateInto(path: String) {
        navigationStack.add(currentPath)
        loadFiles(path)
    }

    private fun navigateBack() {
        if (navigationStack.isNotEmpty()) {
            val previousPath = navigationStack.removeAt(navigationStack.size - 1)
            loadFiles(previousPath)
        }
    }

    private fun refreshCurrentPath() {
        loadFiles(currentPath)
    }

    private fun playVideo(resource: WebDAVResource) {
        val streamUrl = try {
            webdavClient.getStreamUrl(resource.path)
        } catch (e: Exception) {
            Toast.makeText(this, "获取播放地址失败", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("video_url", streamUrl)
            putExtra("video_title", resource.name)
        }
        startActivity(intent)
    }

    private fun showLoading(loading: Boolean) {
        isLoading = loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        if (!loading && adapter.itemCount == 0 && isConnected) {
            layoutEmpty.visibility = View.VISIBLE
        } else if (loading) {
            layoutEmpty.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---- RecyclerView Adapter ----
    class FileAdapter(
        private val onClick: (WebDAVResource) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        private var files = listOf<WebDAVResource>()

        fun submitList(list: List<WebDAVResource>) {
            files = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = files.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(files[position], onClick)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon = itemView.findViewById<TextView>(R.id.iv_icon)
            private val tvName = itemView.findViewById<TextView>(R.id.tv_name)
            private val tvInfo = itemView.findViewById<TextView>(R.id.tv_info)
            private val tvSize = itemView.findViewById<TextView>(R.id.tv_size)

            private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

            fun bind(resource: WebDAVResource, onClick: (WebDAVResource) -> Unit) {
                tvName.text = resource.name

                when {
                    resource.isDirectory -> {
                        ivIcon.text = "📁"
                        tvInfo.text = "目录"
                        tvSize.text = ""
                    }
                    resource.isVideo -> {
                        ivIcon.text = "🎬"
                        tvInfo.text = "视频"
                        tvSize.text = formatSize(resource.size)
                    }
                    resource.isImage -> {
                        ivIcon.text = "🖼️"
                        tvInfo.text = "图片"
                        tvSize.text = formatSize(resource.size)
                    }
                    resource.isAudio -> {
                        ivIcon.text = "🎵"
                        tvInfo.text = "音频"
                        tvSize.text = formatSize(resource.size)
                    }
                    else -> {
                        ivIcon.text = "📄"
                        tvInfo.text = "文件"
                        tvSize.text = formatSize(resource.size)
                    }
                }

                if (resource.lastModified > 0) {
                    tvInfo.text = "${tvInfo.text} · ${
                        dateFormat.format(Date(resource.lastModified))
                    }"
                }

                itemView.setOnClickListener { onClick(resource) }
            }

            private fun formatSize(bytes: Long): String {
                if (bytes <= 0) return ""
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
                    else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
                }
            }
        }
    }
}
