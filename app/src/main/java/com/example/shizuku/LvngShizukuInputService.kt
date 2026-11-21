package com.example.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileDescriptor
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToInt

/**
 * Privileged UserService executed inside Shizuku's process.
 * - Macro Touch Backend: Exclusive non-root kernel-level /dev/uhid virtual HID Multi-Touch Digitizer
 *   ("LVNG Virtual Touch") via hid-multitouch, completely decoupled from physical touchscreen evdev
 *   and InputManager fallback paths. If UHID fails or closes, macro stops safely.
 * - Toggles system show_touches / pointer_location so in-game touch indicators work natively.
 * - Executes privileged background RAM & cache boosting without leaving the foreground game.
 */
@Keep
class LvngShizukuInputService : Binder() {

    private inner class UhIdTouchController {
        private val writeLock = Any()
        private val active = AtomicBoolean(false)
        private val startedLatch = CountDownLatch(1)

        @Volatile
        private var fd: FileDescriptor? = null

        @Volatile
        private var readerThread: Thread? = null

        @Volatile
        private var orientationAware: Boolean? = null

        private val orientationProbeStarted = AtomicBoolean(false)
        private val contactSeq = AtomicLong(0L)

        fun isAlive(): Boolean = active.get() && fd != null

        fun start(): Boolean {
            if (isAlive()) return true

            val openedFd = runCatching {
                Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
            }.getOrNull() ?: return false

            fd = openedFd
            active.set(true)

            val reader = Thread({
                readLoop(openedFd)
            }, "LvngUhIdReader").apply {
                isDaemon = true
                start()
            }
            readerThread = reader

            val createBuf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            createBuf.putInt(UHID_CREATE2)
            putCString(createBuf, 4, 128, UHID_DEVICE_NAME)
            putCString(createBuf, 132, 64, "uhid/lvng_touch")
            putCString(createBuf, 196, 64, "lvng_touch_0")
            createBuf.position(260)
            createBuf.putShort(HID_TOUCHSCREEN_DESCRIPTOR.size.toShort())
            createBuf.putShort(BUS_VIRTUAL.toShort())
            createBuf.putInt(0x18D1)
            createBuf.putInt(0x4EE8)
            createBuf.putInt(1)
            createBuf.putInt(0)
            createBuf.position(280)
            createBuf.put(HID_TOUCHSCREEN_DESCRIPTOR)

            if (!writeUhIdEvent(openedFd, createBuf.array())) {
                close()
                return false
            }

            runCatching { startedLatch.await(300L, TimeUnit.MILLISECONDS) }
            probeOrientationAwareAsync()
            return isAlive()
        }

        private fun putCString(buf: ByteBuffer, offset: Int, maxLen: Int, value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            val copyLen = bytes.size.coerceAtMost(maxLen - 1)
            buf.position(offset)
            buf.put(bytes, 0, copyLen)
            buf.put(0)
        }

        private fun readLoop(openedFd: FileDescriptor) {
            val readBuf = ByteArray(UHID_EVENT_SIZE)
            while (active.get()) {
                val bytesRead = try {
                    Os.read(openedFd, readBuf, 0, readBuf.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR && active.get()) continue
                    break
                } catch (_: Throwable) {
                    break
                }
                if (bytesRead < 4) {
                    if (bytesRead <= 0) break
                    continue
                }
                val bb = ByteBuffer.wrap(readBuf, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                val eventType = bb.getInt(0)
                when (eventType) {
                    UHID_START -> {
                        startedLatch.countDown()
                    }
                    UHID_STOP -> {
                        active.set(false)
                    }
                    UHID_GET_REPORT -> {
                        if (bytesRead >= 10) {
                            val reqId = bb.getInt(4)
                            val rnum = readBuf[8]
                            val rtype = readBuf[9].toInt() and 0xFF
                            if (rtype == UHID_FEATURE_REPORT && (rnum.toInt() and 0xFF) == 0x02) {
                                replyFeatureReport(openedFd, reqId, rnum)
                            } else {
                                replyGetReportError(openedFd, reqId)
                            }
                        }
                    }
                    UHID_SET_REPORT -> {
                        if (bytesRead >= 8) {
                            val reqId = bb.getInt(4)
                            replySetReport(openedFd, reqId)
                        }
                    }
                }
            }
        }

        private fun replyFeatureReport(openedFd: FileDescriptor, reqId: Int, rnum: Byte) {
            val reply = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            reply.putInt(UHID_GET_REPORT_REPLY)
            reply.putInt(reqId)
            reply.putShort(0) // err = 0
            reply.putShort(2) // size = 2
            reply.put(rnum)
            reply.put(0x0A.toByte()) // Contact Count Maximum = 10
            writeUhIdEvent(openedFd, reply.array())
        }

        private fun replyGetReportError(openedFd: FileDescriptor, reqId: Int) {
            val reply = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            reply.putInt(UHID_GET_REPORT_REPLY)
            reply.putInt(reqId)
            reply.putShort(OsConstants.EIO.toShort())
            reply.putShort(0)
            writeUhIdEvent(openedFd, reply.array())
        }

        private fun replySetReport(openedFd: FileDescriptor, reqId: Int) {
            val reply = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            reply.putInt(UHID_SET_REPORT_REPLY)
            reply.putInt(reqId)
            reply.putShort(0)
            writeUhIdEvent(openedFd, reply.array())
        }

        private fun writeUhIdEvent(openedFd: FileDescriptor, packet: ByteArray): Boolean {
            synchronized(writeLock) {
                var offset = 0
                while (offset < packet.size) {
                    try {
                        val written = Os.write(openedFd, packet, offset, packet.size - offset)
                        if (written <= 0) return false
                        offset += written
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EINTR) continue
                        return false
                    } catch (_: Throwable) {
                        return false
                    }
                }
                return true
            }
        }

        private fun probeOrientationAwareAsync() {
            if (orientationAware != null || !orientationProbeStarted.compareAndSet(false, true)) return
            Thread({
                repeat(8) { attempt ->
                    if (!isAlive()) return@Thread
                    val lines = runShellBatch(listOf("dumpsys input 2>/dev/null")).orEmpty()
                    var inDeviceSection = false
                    var foundDevice = false
                    for (line in lines) {
                        if (line.contains(UHID_DEVICE_NAME)) {
                            inDeviceSection = true
                            foundDevice = true
                            continue
                        }
                        if (inDeviceSection) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("OrientationAware:")) {
                                val value = trimmed.substringAfter(':').trim().equals("true", ignoreCase = true)
                                orientationAware = value
                                return@Thread
                            }
                            if (Regex("""^\d+:\s+""").containsMatchIn(trimmed)) {
                                inDeviceSection = false
                            }
                        }
                    }
                    if (foundDevice) {
                        orientationAware = true
                        return@Thread
                    }
                    if (attempt < 7) {
                        sleepPreciseMs(200L, null)
                    }
                }
            }, "LvngUhIdOrientProbe").apply {
                isDaemon = true
                start()
            }
        }

        fun tapOnce(
            xPx: Float,
            yPx: Float,
            screenW: Int,
            screenH: Int,
            rotation: Int,
            holdMs: Long,
            genCheck: Long?
        ): Boolean {
            val openedFd = fd ?: return false
            if (!active.get()) return false

            val nx = (xPx / screenW.coerceAtLeast(1)).coerceIn(0f, 1f)
            val ny = (yPx / screenH.coerceAtLeast(1)).coerceIn(0f, 1f)
            val useOrientationTransform = orientationAware ?: true
            val (rawNx, rawNy) = if (useOrientationTransform) {
                when (rotation) {
                    Surface.ROTATION_90 -> (1f - ny) to nx
                    Surface.ROTATION_180 -> (1f - nx) to (1f - ny)
                    Surface.ROTATION_270 -> ny to (1f - nx)
                    else -> nx to ny
                }
            } else {
                nx to ny
            }

            val rawX = (rawNx * UHID_MAX_AXIS).roundToInt().coerceIn(0, UHID_MAX_AXIS)
            val rawY = (rawNy * UHID_MAX_AXIS).roundToInt().coerceIn(0, UHID_MAX_AXIS)
            val contactId = (((contactSeq.incrementAndGet() - 1L) % 127L) + 1L).toByte()

            var downSent = false
            try {
                val downReport = buildTouchReport(isDown = true, contactId = contactId, rawX = rawX, rawY = rawY)
                if (!writeUhIdEvent(openedFd, downReport)) {
                    close()
                    return false
                }
                downSent = true

                if (holdMs > 0L) {
                    sleepPreciseMs(holdMs, genCheck)
                }
            } finally {
                if (downSent) {
                    val upReport = buildTouchReport(isDown = false, contactId = contactId, rawX = rawX, rawY = rawY)
                    if (!writeUhIdEvent(openedFd, upReport)) {
                        close()
                    }
                }
            }
            return true
        }

        private fun buildTouchReport(isDown: Boolean, contactId: Byte, rawX: Int, rawY: Int): ByteArray {
            // A HID multitouch frame always describes all advertised contact slots.
            // Slot 0 is used by the current macro tap; the remaining slots are explicitly inactive.
            val buf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(UHID_INPUT2)
            buf.putShort(UHID_TOUCH_REPORT_SIZE.toShort())
            buf.put(0x01.toByte()) // Report ID = 1
            repeat(UHID_MAX_CONTACTS) { slot ->
                val activeSlot = slot == 0 && isDown
                buf.put(if (activeSlot) 0x07.toByte() else 0x00.toByte()) // Tip, In Range, Confidence
                buf.put(if (activeSlot) contactId else 0x00.toByte())
                buf.putShort(if (activeSlot) rawX.toShort() else 0)
                buf.putShort(if (activeSlot) rawY.toShort() else 0)
            }
            buf.put(if (isDown) 0x01.toByte() else 0x00.toByte()) // Contact Count
            return buf.array()
        }

        fun close() {
            val prevActive = active.getAndSet(false)
            val openedFd = fd
            fd = null
            orientationAware = null
            orientationProbeStarted.set(false)
            if (prevActive && openedFd != null) {
                val destroyBuf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                destroyBuf.putInt(UHID_DESTROY)
                runCatching { writeUhIdEvent(openedFd, destroyBuf.array()) }
            }
            if (openedFd != null) {
                runCatching { Os.close(openedFd) }
            }
            readerThread?.interrupt()
            readerThread = null
        }
    }

    private val shellLock = Any()
    private val uhidLock = Any()
    private val repeatThreadLock = Any()

    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var shellReader: BufferedReader? = null

    @Volatile
    private var uhidController: UhIdTouchController? = null

    @Volatile
    private var lastUhidProbeMs = 0L

    @Volatile
    private var warmupStarted = false

    private val repeatGeneration = AtomicLong(0L)
    private val highestStoppedSeq = AtomicLong(0L)
    private var repeatThread: Thread? = null

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_TAP -> {
                data.enforceInterface(DESCRIPTOR)
                val x = data.readFloat()
                val y = data.readFloat()
                val durationMs = data.readLong()
                val screenW = if (data.dataAvail() >= 12) data.readInt() else 0
                val screenH = if (data.dataAvail() >= 8) data.readInt() else 0
                val rotation = if (data.dataAvail() >= 4) data.readInt() else 0
                val ok = performTap(x, y, durationMs, screenW, screenH, rotation)
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                true
            }

            TRANSACTION_PING -> {
                data.enforceInterface(DESCRIPTOR)
                triggerAsyncWarmup()
                reply?.writeNoException()
                reply?.writeInt(1)
                true
            }

            TRANSACTION_DESTROY -> {
                data.enforceInterface(DESCRIPTOR)
                stopRepeatTap()
                closeUhId()
                cleanupShell()
                reply?.writeNoException()
                true
            }

            TRANSACTION_START_REPEAT -> {
                data.enforceInterface(DESCRIPTOR)
                val x = data.readFloat()
                val y = data.readFloat()
                val intervalMs = data.readLong()
                val durationMs = data.readLong()
                val screenW = if (data.dataAvail() >= 12) data.readInt() else 0
                val screenH = if (data.dataAvail() >= 8) data.readInt() else 0
                val rotation = if (data.dataAvail() >= 4) data.readInt() else 0
                val seqId = if (data.dataAvail() >= 8) data.readLong() else 0L
                val ok = startRepeatTap(x, y, intervalMs, durationMs, screenW, screenH, rotation, seqId)
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                true
            }

            TRANSACTION_STOP_REPEAT -> {
                data.enforceInterface(DESCRIPTOR)
                val seqId = if (data.dataAvail() >= 8) data.readLong() else 0L
                stopRepeatTap(seqId)
                reply?.writeNoException()
                true
            }

            TRANSACTION_SET_SETTING -> {
                data.enforceInterface(DESCRIPTOR)
                val namespace = data.readString().orEmpty()
                val key = data.readString().orEmpty()
                val value = data.readInt()
                val ok = setSystemSetting(namespace, key, value)
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                true
            }

            TRANSACTION_BOOST -> {
                data.enforceInterface(DESCRIPTOR)
                val activeGamePkg = data.readString().orEmpty()
                val callerPkg = data.readString().orEmpty()
                val candidatePkgs = data.createStringArray() ?: emptyArray()
                val boostedCount = performRealDeviceBoost(activeGamePkg, callerPkg, candidatePkgs)
                reply?.writeNoException()
                reply?.writeInt(boostedCount)
                true
            }

            TRANSACTION_IS_REPEAT_ACTIVE -> {
                data.enforceInterface(DESCRIPTOR)
                val isRunning = synchronized(repeatThreadLock) {
                    repeatThread?.isAlive == true && uhidController?.isAlive() == true
                }
                reply?.writeNoException()
                reply?.writeInt(if (isRunning) 1 else 0)
                true
            }

            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun triggerAsyncWarmup() {
        if (warmupStarted) return
        warmupStarted = true
        Thread({
            resolveUhIdTouch()
        }, "LvngInputWarmup").apply {
            isDaemon = true
            start()
        }
    }

    private fun resolveUhIdTouch(): UhIdTouchController? {
        uhidController?.takeIf { it.isAlive() }?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (lastUhidProbeMs != 0L && (now - lastUhidProbeMs) < PROBE_RETRY_COOLDOWN_MS) return null
        synchronized(uhidLock) {
            uhidController?.let { existing ->
                if (existing.isAlive()) return existing
                existing.close()
                uhidController = null
            }
            val nowLocked = SystemClock.elapsedRealtime()
            if (lastUhidProbeMs != 0L && (nowLocked - lastUhidProbeMs) < PROBE_RETRY_COOLDOWN_MS) return null
            lastUhidProbeMs = nowLocked
            val controller = UhIdTouchController()
            return if (controller.start()) {
                uhidController = controller
                controller
            } else {
                controller.close()
                null
            }
        }
    }

    private fun closeUhId() {
        synchronized(uhidLock) {
            uhidController?.close()
            uhidController = null
            lastUhidProbeMs = 0L
        }
    }

    private fun sleepPreciseMs(ms: Long, genCheck: Long? = null) {
        if (ms <= 0L) return
        val deadlineNs = System.nanoTime() + ms * 1_000_000L
        while (true) {
            if (genCheck != null && repeatGeneration.get() != genCheck) return
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) return
            LockSupport.parkNanos(remainingNs.coerceAtMost(5_000_000L))
        }
    }

    private fun performTap(
        x: Float,
        y: Float,
        durationMs: Long,
        screenW: Int,
        screenH: Int,
        rotation: Int
    ): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val clampedDuration = durationMs.coerceIn(18L, 1000L)
        val holdMs = clampedDuration.coerceAtMost(400L)

        val uhid = resolveUhIdTouch() ?: return false
        val tapped = uhid.tapOnce(safeX, safeY, screenW, screenH, rotation, holdMs, null)
        if (!tapped) {
            closeUhId()
        }
        return tapped
    }

    private fun startRepeatTap(
        x: Float,
        y: Float,
        intervalMs: Long,
        durationMs: Long,
        screenW: Int,
        screenH: Int,
        rotation: Int,
        seqId: Long = 0L
    ): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val clampedInterval = intervalMs.coerceIn(10L, 1000L)
        val effectiveHoldMs = if (clampedInterval <= 25L) {
            durationMs.coerceIn(18L, 26L)
        } else {
            durationMs.coerceIn(18L, 320L)
        }
        val effectiveGapMs = clampedInterval.coerceAtLeast(14L)

        // UHID-only: do NOT fall back to physical touchscreen evdev or InputManager
        val initialUhId = resolveUhIdTouch() ?: return false

        synchronized(repeatThreadLock) {
            if (seqId > 0L && seqId <= highestStoppedSeq.get()) {
                return false
            }

            val myGen = repeatGeneration.incrementAndGet()
            repeatThread?.let { prev ->
                LockSupport.unpark(prev)
                prev.interrupt()
                runCatching { prev.join(180L) }
            }

            val worker = Thread({
                try {
                    runCatching {
                        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_DISPLAY)
                    }

                    var activeUhId: UhIdTouchController? = initialUhId
                    while (repeatGeneration.get() == myGen && !Thread.currentThread().isInterrupted) {
                        val uhid = activeUhId?.takeIf { it.isAlive() } ?: break
                        val tapped = uhid.tapOnce(safeX, safeY, screenW, screenH, rotation, effectiveHoldMs, myGen)
                        if (!tapped) {
                            // UHID failed: stop macro safely without taking over physical touchscreen
                            closeUhId()
                            activeUhId = null
                            break
                        }

                        if (repeatGeneration.get() != myGen) break
                        sleepPreciseMs(effectiveGapMs, myGen)
                    }
                } finally {
                    synchronized(repeatThreadLock) {
                        if (repeatThread === Thread.currentThread()) {
                            repeatThread = null
                        }
                    }
                }
            }, "LvngMacroLoop").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
            repeatThread = worker
            worker.start()
        }
        return true
    }

    private fun stopRepeatTap(seqId: Long = 0L) {
        synchronized(repeatThreadLock) {
            if (seqId > 0L) {
                while (true) {
                    val prev = highestStoppedSeq.get()
                    if (seqId <= prev || highestStoppedSeq.compareAndSet(prev, seqId)) break
                }
            }
            repeatGeneration.incrementAndGet()
            repeatThread?.let { thread ->
                LockSupport.unpark(thread)
                thread.interrupt()
                runCatching { thread.join(180L) }
            }
            repeatThread = null
        }
    }

    private fun setSystemSetting(namespace: String, key: String, value: Int): Boolean {
        val safeNs = when (namespace) {
            "system", "secure", "global" -> namespace
            else -> return false
        }
        val allowedKeys = setOf("show_touches", "pointer_location", "accelerometer_rotation")
        if (key !in allowedKeys) return false
        val safeVal = if (value != 0) 1 else 0
        val out = runShellBatch(listOf("settings put $safeNs $key $safeVal"))
        return out != null
    }

    private fun performRealDeviceBoost(
        activeGamePackage: String,
        callerPackage: String,
        candidatePackages: Array<String>
    ): Int {
        val protectedExact = HashSet<String>().apply {
            add("android")
            add("com.android.systemui")
            add("com.android.phone")
            add("com.android.bluetooth")
            add("com.android.settings")
            add("com.google.android.gms")
            add("com.google.android.gsf")
            add("moe.shizuku.privileged.api")
            if (callerPackage.isNotBlank()) add(callerPackage)
            if (activeGamePackage.isNotBlank()) add(activeGamePackage)
        }

        // Discover active default launcher & active keyboard so we never trim/kill them
        val envInfo = runShellBatch(
            listOf(
                "cmd package resolve-activity --brief -c android.intent.category.HOME 2>/dev/null | tail -n 1",
                "settings get secure default_input_method 2>/dev/null"
            )
        ).orEmpty()

        envInfo.forEach { line ->
            val pkg = line.trim().substringBefore('/').trim()
            if (pkg.contains('.') && !pkg.contains(' ')) {
                protectedExact.add(pkg)
            }
        }

        val sanitizedTargets = candidatePackages
            .map { it.trim() }
            .filter { pkg ->
                pkg.isNotBlank() &&
                    pkg !in protectedExact &&
                    pkg.matches(Regex("[a-zA-Z0-9_\\.]+"))
            }
            .distinct()
            .take(60)

        val commands = mutableListOf<String>()
        commands.add("am kill-all 2>/dev/null")
        sanitizedTargets.forEach { pkg ->
            commands.add("am send-trim-memory $pkg COMPLETE 2>/dev/null; am kill $pkg 2>/dev/null")
        }
        commands.add("pm trim-caches 1024M 2>/dev/null")
        commands.add("cmd power set-fixed-performance-mode-enabled true 2>/dev/null")
        commands.add("sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null")

        runShellBatch(commands)
        return sanitizedTargets.size
    }

    private fun ensureShellLocked(): Boolean {
        val proc = shellProcess
        if (proc != null && proc.isAlive && shellWriter != null && shellReader != null) {
            return true
        }
        cleanupShellLocked()
        return runCatching {
            val started = ProcessBuilder("sh").redirectErrorStream(true).start()
            shellProcess = started
            shellWriter = BufferedWriter(OutputStreamWriter(started.outputStream))
            shellReader = BufferedReader(InputStreamReader(started.inputStream))
            true
        }.getOrElse {
            cleanupShellLocked()
            false
        }
    }

    private fun runShellBatch(commands: List<String>): List<String>? {
        val marker = "__LVNG_DONE_${System.nanoTime()}__"
        synchronized(shellLock) {
            if (!ensureShellLocked()) return null
            return runCatching {
                val writer = shellWriter!!
                val reader = shellReader!!
                commands.forEach { cmd ->
                    writer.write(cmd)
                    writer.newLine()
                }
                writer.write("echo $marker")
                writer.newLine()
                writer.flush()

                val lines = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == marker) break
                    lines.add(line)
                }
                lines
            }.getOrElse {
                cleanupShellLocked()
                null
            }
        }
    }

    private fun cleanupShell() {
        synchronized(shellLock) {
            cleanupShellLocked()
        }
    }

    private fun cleanupShellLocked() {
        runCatching { shellWriter?.close() }
        runCatching { shellReader?.close() }
        runCatching { shellProcess?.destroy() }
        shellWriter = null
        shellReader = null
        shellProcess = null
    }

    companion object {
        const val DESCRIPTOR = "com.example.shizuku.ILvngInputService"
        const val TRANSACTION_TAP = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_DESTROY = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_START_REPEAT = IBinder.FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_STOP_REPEAT = IBinder.FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_SET_SETTING = IBinder.FIRST_CALL_TRANSACTION + 6
        const val TRANSACTION_BOOST = IBinder.FIRST_CALL_TRANSACTION + 7
        const val TRANSACTION_IS_REPEAT_ACTIVE = IBinder.FIRST_CALL_TRANSACTION + 8

        private const val PROBE_RETRY_COOLDOWN_MS = 4_000L

        private const val UHID_DESTROY = 1
        private const val UHID_START = 2
        private const val UHID_STOP = 3
        private const val UHID_GET_REPORT = 9
        private const val UHID_GET_REPORT_REPLY = 10
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_SET_REPORT = 13
        private const val UHID_SET_REPORT_REPLY = 14
        private const val UHID_FEATURE_REPORT = 0
        private const val UHID_EVENT_SIZE = 4380
        private const val UHID_MAX_AXIS = 32767
        private const val BUS_VIRTUAL = 0x0006
        private const val UHID_DEVICE_NAME = "LVNG Virtual Touch"

        private const val UHID_MAX_CONTACTS = 10
        private const val UHID_TOUCH_REPORT_SIZE = 1 + (UHID_MAX_CONTACTS * 6) + 1

        private val HID_TOUCHSCREEN_DESCRIPTOR = buildHidTouchscreenDescriptor()

        /** Builds a standard parallel-mode HID multitouch descriptor with ten contact collections. */
        private fun buildHidTouchscreenDescriptor(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            fun bytes(vararg values: Int) {
                values.forEach { out.write(it and 0xFF) }
            }

            // Digitizer / Touch Screen application collection and input report ID.
            bytes(
                0x05, 0x0D,             // Usage Page (Digitizers)
                0x09, 0x04,             // Usage (Touch Screen)
                0xA1, 0x01,             // Collection (Application)
                0x85, 0x01              // Report ID (1)
            )

            repeat(UHID_MAX_CONTACTS) {
                bytes(
                    0x09, 0x22,             // Usage (Finger)
                    0xA1, 0x02,             // Collection (Logical)
                    0x09, 0x42,             // Usage (Tip Switch)
                    0x09, 0x32,             // Usage (In Range)
                    0x09, 0x47,             // Usage (Confidence)
                    0x15, 0x00,             // Logical Minimum (0)
                    0x25, 0x01,             // Logical Maximum (1)
                    0x75, 0x01,             // Report Size (1)
                    0x95, 0x03,             // Report Count (3)
                    0x81, 0x02,             // Input (Data, Variable, Absolute)
                    0x75, 0x05,             // Report Size (5)
                    0x95, 0x01,             // Report Count (1)
                    0x81, 0x03,             // Input (Constant)
                    0x75, 0x08,             // Report Size (8)
                    0x95, 0x01,             // Report Count (1)
                    0x09, 0x51,             // Usage (Contact Identifier)
                    0x15, 0x00,             // Logical Minimum (0)
                    0x25, 0x7F,             // Logical Maximum (127)
                    0x81, 0x02,             // Input (Data, Variable, Absolute)
                    0x05, 0x01,             // Usage Page (Generic Desktop)
                    0x26, 0xFF, 0x7F,       // Logical Maximum (32767)
                    0x46, 0xFF, 0x7F,       // Physical Maximum (32767)
                    0x75, 0x10,             // Report Size (16)
                    0x95, 0x02,             // Report Count (2)
                    0x09, 0x30,             // Usage (X)
                    0x09, 0x31,             // Usage (Y)
                    0x81, 0x02,             // Input (Data, Variable, Absolute)
                    0xC0                    // End Collection (Finger)
                )
            }

            bytes(
                0x05, 0x0D,             // Usage Page (Digitizers)
                0x15, 0x00,             // Logical Minimum (0)
                0x25, UHID_MAX_CONTACTS,// Logical Maximum (10)
                0x75, 0x08,             // Report Size (8)
                0x95, 0x01,             // Report Count (1)
                0x09, 0x54,             // Usage (Contact Count)
                0x81, 0x02,             // Input (Data, Variable, Absolute)
                0x85, 0x02,             // Report ID (2)
                0x09, 0x55,             // Usage (Contact Count Maximum)
                0xB1, 0x02,             // Feature (Data, Variable, Absolute)
                0xC0                    // End Collection (Application)
            )
            return out.toByteArray()
        }
    }
}
