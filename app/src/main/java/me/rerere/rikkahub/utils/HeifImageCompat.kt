package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * v1017：官方 3.1.1 把 ImageUtils 里的 HEIF/HEIC 两个辅助函数删掉了（他们顺带把裁剪
 * 从 uCrop 换成了 Compose 的 ImageCropDialog）。但「裁剪前先把 HDR HEIF 转成 JPEG」
 * 是我们自己的二改（v2xx 为规避裁剪解码失败而加），删掉就会让 iPhone 拍的 HEIC 再次
 * 裁不动。因此把这两个函数收进我们独有的兼容文件，继续复用官方仍保留的
 * calculateInSampleSize / correctImageOrientation / recycleBitmapSafely。
 *
 * 放在独立文件里而不是改回 ImageUtils.kt，是为了让 ImageUtils.kt 保持与上游逐字节一致，
 * 下次合并上游时它属于「可整覆盖」一侧。
 */
object HeifImageCompat {
    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis",
        "hevc", "hevx", "hevm", "hevs",
        "mif1", "msf1", "heif",
    )

    /**
     * 判断 uri 指向的图片是否为 HEIF/HEIC 格式
     * 优先使用 ContentResolver 的 MIME 类型，回退到读取文件头魔数嗅探
     */
    fun isHeifImage(context: Context, uri: Uri): Boolean {
        context.contentResolver.getType(uri)?.lowercase()?.let { mime ->
            if (mime.contains("heic") || mime.contains("heif")) return true
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read < 12) return@use false
                if (header.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") return@use false
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) in HEIF_BRANDS
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * 将 HEIF/HEIC 图片解码后重编码为 JPEG 写入 [target]
     * 用于规避部分组件对 HEIF（尤其 HDR HEIF）解码兼容性不佳的问题
     *
     * @return 转换成功返回 true，失败（如系统无法解码 HEIF）返回 false
     */
    fun convertHeifToJpeg(
        context: Context,
        uri: Uri,
        target: File,
        maxSize: Int = 4096,
        quality: Int = 95,
    ): Boolean = runCatching {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }
        val loadOptions = BitmapFactory.Options().apply {
            inSampleSize = ImageUtils.calculateInSampleSize(boundsOptions, maxSize, maxSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, loadOptions)
        } ?: return@runCatching false
        // 将 EXIF 旋转烘焙进像素，输出的 JPEG 不再带方向信息，避免下游二次旋转
        val oriented = ImageUtils.correctImageOrientation(context, uri, decoded)
        try {
            target.outputStream().use { output ->
                oriented.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }
            true
        } finally {
            ImageUtils.recycleBitmapSafely(oriented)
        }
    }.onFailure {
        it.printStackTrace()
    }.getOrDefault(false)
}
