package com.leohao.android.alistlite.webdav

data class WebDAVResource(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val contentType: String? = null,
    val resourceType: ResourceType = ResourceType.OTHER
) {
    val isVideo: Boolean get() = resourceType == ResourceType.VIDEO
    val isImage: Boolean get() = resourceType == ResourceType.IMAGE
    val isAudio: Boolean get() = resourceType == ResourceType.AUDIO

    companion object {
        fun determineResourceType(name: String, contentType: String?, isDirectory: Boolean): ResourceType {
            if (isDirectory) return ResourceType.DIRECTORY

            val lowerName = name.lowercase()
            val lowerContentType = contentType?.lowercase()

            val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp")
            val videoMimeTypes = listOf("video/", "application/x-mpegurl", "application/vnd.apple.mpegurl")
            if (videoExtensions.any { lowerName.endsWith(it) } ||
                videoMimeTypes.any { lowerContentType?.startsWith(it) == true }) {
                return ResourceType.VIDEO
            }

            val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".heic", ".heif")
            val imageMimeTypes = listOf("image/")
            if (imageExtensions.any { lowerName.endsWith(it) } ||
                imageMimeTypes.any { lowerContentType?.startsWith(it) == true }) {
                return ResourceType.IMAGE
            }

            val audioExtensions = listOf(".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".wma")
            val audioMimeTypes = listOf("audio/")
            if (audioExtensions.any { lowerName.endsWith(it) } ||
                audioMimeTypes.any { lowerContentType?.startsWith(it) == true }) {
                return ResourceType.AUDIO
            }

            return ResourceType.OTHER
        }
    }
}
