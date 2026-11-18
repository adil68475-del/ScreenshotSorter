herepackage com.sorter.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.util.concurrent.Executors

private val MEDIA_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mkv", "3gp", "webm", "mov")
private val VIDEO_EXT = setOf("mp4", "mkv", "3gp", "webm", "mov")
