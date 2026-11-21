package com.example.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class ShizukuLiveStatus(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val isUserServiceBound: Boolean = false
) {
    val isReady: Boolean
        get() = isRunning && isPermissionGranted
}

/**
 * Manages live Shizuku binder detection, runtime permission prompts ("Allow LVNG SPACE"),
 * and privileged UserService binding for Macro auto-tap, in-game touch visibility, and device boost.
 */
object LvngShizukuManager {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_PERMISSION_REQ_CODE = 10421
    private const val BIND_TIMEOUT_MS = 8_000L
    private const val BIND_COOLDOWN_MS = 2_500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindLock = Any()

    private val _status = MutableStateFlow(ShizukuLiveStatus())
    val status: StateFlow<ShizukuLiveStatus> = _status.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remoteInputBinder: IBinder? = null

    @Volatile
    private var isBindingInProgress = false

    @Volatile
    private var lastBindAttemptElapsedMs = 0L

    @Volatile
    private var listenersRegistered = false

    private val repeatSeqCounter = AtomicLong(0L)
    private val minValidRepeatSeq = AtomicLong(0L)
    private val repeatStopExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LvngMacroStop").apply { isDaemon = true }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        mainHandler.post {
            runCatching { refreshStatus() }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(bindLock) {
            remoteInputBinder = null
            isBindingInProgress = false
        }
        mainHandler.post {
            runCatching { refreshStatus() }
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQ_CODE) {
                mainHandler.post {
                    runCatching {
                        val snapshot = refreshStatus()
                        if (grantResult == PackageManager.PERMISSION_GRANTED && snapshot.isReady) {
                            bindUserServiceIfNeeded()
                        }
                    }
                }
            }
        }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(bindLock) {
                remoteInputBinder = service
                isBindingInProgress = false
            }
            val alive = runCatching { service?.isBinderAlive == true }.getOrDefault(false)
            _status.value = _status.value.copy(isUserServiceBound = alive)
            if (alive && service != null) {
                warmupTouchBackendAsync(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindLock) {
                remoteInputBinder = null
                isBindingInProgress = false
            }
            _status.value = _status.value.copy(isUserServiceBound = false)
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        synchronized(bindLock) {
            if (!listenersRegistered) {
                listenersRegistered = true
                runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, mainHandler) }
                runCatching { Shizuku.addBinderDeadListener(binderDeadListener, mainHandler) }
                runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener, mainHandler) }
            }
        }
        refreshStatus(ctx)
    }

    /**
     * Pure status inspection — never triggers bindUserService() recursively so it cannot
     * overflow the stack when permission becomes granted or during startup/lifecycle callbacks.
     */
    fun refreshStatus(context: Context? = appContext): ShizukuLiveStatus {
        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) appContext = ctx

        val installed = ctx?.let { isPackageInstalled(it, SHIZUKU_PACKAGE) } ?: false
        val running = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11()
        }.getOrDefault(false)

        val granted = if (running) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            false
        }

        val bound: Boolean
        synchronized(bindLock) {
            if (!running || !granted) {
                remoteInputBinder = null
                isBindingInProgress = false
            }
            bound = runCatching { remoteInputBinder?.isBinderAlive == true }.getOrDefault(false)
            if (!bound && remoteInputBinder != null) {
                remoteInputBinder = null
            }
        }

        val snapshot = ShizukuLiveStatus(
            isInstalled = installed,
            isRunning = running,
            isPermissionGranted = granted,
            isUserServiceBound = bound
        )
        _status.value = snapshot
        return snapshot
    }

    fun requestPermission(context: Context): Boolean {
        val current = refreshStatus(context)
        if (!current.isRunning) return false
        if (current.isPermissionGranted) {
            bindUserServiceIfNeeded()
            return true
        }
        return runCatching {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ_CODE)
            true
        }.getOrDefault(false)
    }

    fun openShizukuApp(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    fun isReady(context: Context? = appContext): Boolean = refreshStatus(context).isReady

    fun bindUserServiceIfNeeded() {
        val ctx = appContext ?: return
        val statusSnapshot = refreshStatus(ctx)
        if (!statusSnapshot.isReady) return

        val now = SystemClock.elapsedRealtime()
        synchronized(bindLock) {
            if (remoteInputBinder?.isBinderAlive == true) return
            if (isBindingInProgress && (now - lastBindAttemptElapsedMs) < BIND_TIMEOUT_MS) return
            if (!isBindingInProgress && lastBindAttemptElapsedMs != 0L && (now - lastBindAttemptElapsedMs) < BIND_COOLDOWN_MS) return
            isBindingInProgress = true
            lastBindAttemptElapsedMs = now
        }

        val bindBlock = Runnable {
            val currentReady = runCatching {
                Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (!currentReady) {
                synchronized(bindLock) { isBindingInProgress = false }
                return@Runnable
            }
            val args = Shizuku.UserServiceArgs(
                ComponentName(ctx.packageName, LvngShizukuInputService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("lvng_input")
                .debuggable(false)
                .version(6)

            runCatching {
                Shizuku.bindUserService(args, userServiceConnection)
            }.onFailure {
                synchronized(bindLock) {
                    isBindingInProgress = false
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            bindBlock.run()
        } else {
            mainHandler.post(bindBlock)
        }
    }

    private fun getAliveBinderFast(): IBinder? {
        val existing = synchronized(bindLock) {
            remoteInputBinder?.takeIf { runCatching { it.isBinderAlive }.getOrDefault(false) }
        }
        if (existing != null) return existing
        if (refreshStatus().isReady) {
            bindUserServiceIfNeeded()
        }
        return null
    }

    private fun warmupTouchBackendAsync(binder: IBinder) {
        Thread({
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LvngShizukuInputService.DESCRIPTOR)
                binder.transact(LvngShizukuInputService.TRANSACTION_PING, data, reply, 0)
                reply.readException()
            } catch (_: Throwable) {
                // Non-fatal; warmup will run lazily on first tap if needed
            } finally {
                data.recycle()
                reply.recycle()
            }
        }, "LvngShizukuWarmup").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleBinderFailure() {
        synchronized(bindLock) {
            remoteInputBinder = null
            isBindingInProgress = false
        }
        bindUserServiceIfNeeded()
    }

    suspend fun dispatchClickAt(
        xPx: Float,
        yPx: Float,
        durationMs: Long,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        rotation: Int = 0
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val binder = getAliveBinderFast() ?: return@withContext false

            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LvngShizukuInputService.DESCRIPTOR)
                data.writeFloat(xPx)
                data.writeFloat(yPx)
                data.writeLong(durationMs)
                data.writeInt(screenWidth)
                data.writeInt(screenHeight)
                data.writeInt(rotation)
                val transacted = binder.transact(
                    LvngShizukuInputService.TRANSACTION_TAP,
                    data,
                    reply,
                    0
                )
                if (!transacted) return@withContext false
                reply.readException()
                reply.readInt() == 1
            } catch (_: Throwable) {
                handleBinderFailure()
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun startRepeatTap(
        xPx: Float,
        yPx: Float,
        intervalMs: Long,
        durationMs: Long,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        rotation: Int = 0
    ): Boolean {
        val seqId = repeatSeqCounter.incrementAndGet()
        if (seqId <= minValidRepeatSeq.get()) return false

        val binder = getAliveBinderFast() ?: return false
        if (seqId <= minValidRepeatSeq.get()) return false

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(LvngShizukuInputService.DESCRIPTOR)
            data.writeFloat(xPx)
            data.writeFloat(yPx)
            data.writeLong(intervalMs)
            data.writeLong(durationMs)
            data.writeInt(screenWidth)
            data.writeInt(screenHeight)
            data.writeInt(rotation)
            data.writeLong(seqId)
            val transacted = binder.transact(
                LvngShizukuInputService.TRANSACTION_START_REPEAT,
                data,
                reply,
                0
            )
            if (!transacted) return false
            reply.readException()
            val started = reply.readInt() == 1
            if (started && seqId <= minValidRepeatSeq.get()) {
                dispatchOrderedStop(binder, minValidRepeatSeq.get())
                return false
            }
            started
        } catch (_: Throwable) {
            handleBinderFailure()
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun stopRepeatTap() {
        val stopSeq = repeatSeqCounter.incrementAndGet()
        while (true) {
            val prev = minValidRepeatSeq.get()
            if (stopSeq <= prev || minValidRepeatSeq.compareAndSet(prev, stopSeq)) break
        }

        val binder = synchronized(bindLock) {
            remoteInputBinder?.takeIf { runCatching { it.isBinderAlive }.getOrDefault(false) }
        } ?: return

        dispatchOrderedStop(binder, stopSeq)
    }

    private fun dispatchOrderedStop(binder: IBinder, stopSeq: Long) {
        repeatStopExecutor.execute {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LvngShizukuInputService.DESCRIPTOR)
                data.writeLong(stopSeq)
                val transacted = binder.transact(
                    LvngShizukuInputService.TRANSACTION_STOP_REPEAT,
                    data,
                    reply,
                    0
                )
                if (transacted) {
                    reply.readException()
                }
            } catch (_: Throwable) {
                handleBinderFailure()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    suspend fun isRepeatTapActive(): Boolean {
        return withContext(Dispatchers.IO) {
            val binder = synchronized(bindLock) {
                remoteInputBinder?.takeIf { runCatching { it.isBinderAlive }.getOrDefault(false) }
            } ?: return@withContext false

            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LvngShizukuInputService.DESCRIPTOR)
                val transacted = binder.transact(
                    LvngShizukuInputService.TRANSACTION_IS_REPEAT_ACTIVE,
                    data,
                    reply,
                    0
                )
                if (!transacted) return@withContext false
                reply.readException()
                reply.readInt() == 1
            } catch (_: Throwable) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    suspend fun setSystemSetting(namespace: String, key: String, value: Int): Boolean {
        return withContext(Dispatchers.IO) {
            val binder = getAliveBinderFast() ?: return@withContext false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LvngShizukuInputService.DESCRIPTOR)
                data.writeString(namespace)
                data.writeString(key)
                data.writeInt(value)
                val transacted = binder.transact(
                    LvngShizukuInputService.TRANSACTION_SET_SETTING,
                    data,
                    reply,
                    0
                )
                if (!transacted) return@withContext false
                reply.readException()
                reply.readInt() == 1
            } catch (_: Throwable) {
                handleBinderFailure()
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    suspend fun performBoost(
        activeGamePackage: String?,
        callerPackage: String,
        candidatePackages: List<String>
    ): Int {
        return withContext(Dispatchers.IO) {
            val binder = getAliveBinderFast() ?: return@withContext 0
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LvngShizukuInputService.DESCRIPTOR)
                data.writeString(activeGamePackage.orEmpty())
                data.writeString(callerPackage)
                data.writeStringArray(candidatePackages.toTypedArray())
                val transacted = binder.transact(
                    LvngShizukuInputService.TRANSACTION_BOOST,
                    data,
                    reply,
                    0
                )
                if (!transacted) return@withContext 0
                reply.readException()
                reply.readInt()
            } catch (_: Throwable) {
                handleBinderFailure()
                0
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }
}
