package com.privateassistant.platform.documents

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrTaskLifetimeInstrumentedTest {
    @Test
    fun interruptedWaitRetainsBitmapAndHeavyPermitUntilTaskActuallyFinishes() {
        val task = TaskCompletionSource<String>()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val competitorEntered = CountDownLatch(1)
        val restoredInterrupt = AtomicBoolean(false)
        val worker = Thread {
            try {
                HeavyWorkGate.run {
                    entered.countDown()
                    try { SettledTaskAwaiter.await(task.task) }
                    finally { bitmap.recycle() }
                }
                restoredInterrupt.set(Thread.currentThread().isInterrupted)
            } finally { returned.countDown() }
        }
        val competitor = Thread { HeavyWorkGate.run { competitorEntered.countDown() } }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            competitor.start()
            worker.interrupt()
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            assertFalse(bitmap.isRecycled)
            assertFalse(competitorEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            task.trySetResult("synthetic completion")
            worker.join(5000)
            if (competitor.state != Thread.State.NEW) competitor.join(5000)
        }
        assertTrue(returned.count == 0L)
        assertTrue(bitmap.isRecycled)
        assertTrue(restoredInterrupt.get())
        assertTrue(competitorEntered.count == 0L)
    }
}
