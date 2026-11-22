package io.github.jackwener.dsh.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.jackwener.dsh.MainActivity

/**
 * The desktop pet: a sprite from `assets/pets/<id>/` drawn in a floating window over everything.
 * Tap plays a reaction, drag moves it, long press puts it away; the notification can do that too.
 *
 * Started from [PetOverlay.show] (or the 桌宠 quick-settings tile, [PetTile]); needs
 * SYSTEM_ALERT_WINDOW, which the app already declares.
 */
class PetOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var view: PetView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        PetOverlay.running = true
        if (Settings.canDrawOverlays(this)) showPet() else PetOverlay.openPermission(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { hidePet(); stopSelf(); return START_NOT_STICKY }
            ACTION_SHOW -> if (view == null && Settings.canDrawOverlays(this)) showPet()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hidePet()
        PetOverlay.running = false
        super.onDestroy()
    }

    private fun showPet() {
        if (view != null) return
        val pack = PetPack.load(this) ?: run {
            Toast.makeText(this, "宠物包读取失败（assets/pets/${PetPack.DEFAULT_ID}）", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        val bodyPx = (pack.content.height() * resources.displayMetrics.density).toInt()
            .coerceAtLeast(96)
        val pet = PetView(
            context = this,
            pack = pack,
            bodyHeightPx = (bodyPx * 1.6f),
            onDrag = { dx, dy -> move(dx, dy) },
            onLongPress = { hidePet(); stopSelf() },
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = resources.displayMetrics.widthPixels / 2
        lp.y = resources.displayMetrics.heightPixels / 2
        params = lp
        view = pet
        runCatching { windowManager.addView(pet, lp) }.onFailure {
            Toast.makeText(this, "无法显示桌宠：$it", Toast.LENGTH_LONG).show()
            view = null
            stopSelf()
        }
    }

    private fun move(dx: Float, dy: Float) {
        val lp = params ?: return
        val pet = view ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { windowManager.updateViewLayout(pet, lp) }
    }

    private fun hidePet() {
        view?.let { v -> runCatching { windowManager.removeView(v) } }
        view = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "桌宠悬浮层常驻提示"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val close = PendingIntent.getService(
            this, 1,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("东海帝皇在你桌面上")
            .setContentText("点一下有反应 · 拖动可移动 · 长按收起")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "收起", close)
            .build()
    }

    companion object {
        const val ACTION_SHOW = "io.github.jackwener.dsh.pet.SHOW"
        const val ACTION_HIDE = "io.github.jackwener.dsh.pet.HIDE"
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 4312

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PetOverlayService::class.java).setAction(ACTION_SHOW),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PetOverlayService::class.java).setAction(ACTION_HIDE))
        }
    }
}

/** Small facade the app (and the tile) call; keeps [running] so a toggle knows which way to go. */
object PetOverlay {
    @Volatile var running: Boolean = false

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        if (!canShow(context)) { openPermission(context); return }
        PetOverlayService.start(context)
    }

    fun hide(context: Context) = PetOverlayService.stop(context)

    fun toggle(context: Context) {
        if (running) hide(context) else show(context)
    }

    fun openPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "请在系统设置里允许「显示在其他应用上层」", Toast.LENGTH_LONG).show() }
    }
}
