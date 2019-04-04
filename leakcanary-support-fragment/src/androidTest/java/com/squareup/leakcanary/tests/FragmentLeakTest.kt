package com.squareup.leakcanary.tests

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.MessageQueue
import android.view.View
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.rule.ActivityTestRule
import com.squareup.leakcanary.InstrumentationLeakDetector
import com.squareup.leakcanary.InstrumentationLeakResults
import com.squareup.leakcanary.LeakCanary
import com.squareup.leakcanary.internal.ActivityLifecycleCallbacksAdapter
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch

class FragmentLeakTest {

  @get:Rule
  var activityRule = ActivityTestRule(TestActivity::class.java, !TOUCH_MODE, !LAUNCH_ACTIVITY)

  @Before fun setUp() {
    LeakCanary.refWatcher
        .clearWatchedReferences()
  }

  @After fun tearDown() {
    LeakCanary.refWatcher
        .clearWatchedReferences()
  }

  @Test
  fun fragmentShouldLeak() {
    startActivityAndWaitForCreate()

    LeakingFragment.add(activityRule.activity)

    val waitForFragmentDetach = activityRule.activity.waitForFragmentDetached()
    val waitForActivityDestroy = waitForActivityDestroy()
    activityRule.finishActivity()
    waitForFragmentDetach.await()
    waitForActivityDestroy.await()

    assertLeak(LeakingFragment::class.java)
  }

  @Test
  fun fragmentViewShouldLeak() {
    startActivityAndWaitForCreate()
    val activity = activityRule.activity

    val waitForFragmentViewDestroyed = activity.waitForFragmentViewDestroyed()
    // First, add a new fragment
    ViewLeakingFragment.addToBackstack(activity)
    // Then, add a new fragment again, which destroys the view of the previous fragment and puts
    // that fragment in the backstack.
    ViewLeakingFragment.addToBackstack(activity)
    waitForFragmentViewDestroyed.await()

    assertLeak(View::class.java)
  }

  private fun startActivityAndWaitForCreate() {
    val waitForActivityOnCreate = CountDownLatch(1)
    val app = getApplicationContext<Application>()
    app.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacksAdapter() {
      override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?
      ) {
        app.unregisterActivityLifecycleCallbacks(this)
        waitForActivityOnCreate.countDown()
      }
    })

    activityRule.launchActivity(null)

    try {
      waitForActivityOnCreate.await()
    } catch (e: InterruptedException) {
      throw RuntimeException(e)
    }
  }

  private fun assertLeak(expectedLeakClass: Class<*>) {
    val leakDetector = InstrumentationLeakDetector()
    val results = leakDetector.detectLeaks()

    if (results.detectedLeaks.size != 1) {
      throw AssertionError(
          "Expected exactly one leak, not ${results.detectedLeaks.size}" + resultsAsString(
              results.detectedLeaks
          )
      )
    }

    val firstResult = results.detectedLeaks[0]

    val leakingClassName = firstResult.analysisResult.className

    if (leakingClassName != expectedLeakClass.name) {
      throw AssertionError(
          "Expected a leak of $expectedLeakClass, not $leakingClassName" + resultsAsString(
              results.detectedLeaks
          )
      )
    }
  }

  private fun resultsAsString(results: List<InstrumentationLeakResults.Result>): String {
    val context = getApplicationContext<Context>()
    val message = StringBuilder()
    message.append("\nLeaks found:\n##################\n")
    results.forEach { detectedLeak ->
      message.append(
          LeakCanary.leakInfo(context, detectedLeak.heapDump, detectedLeak.analysisResult, false)
      )
    }
    message.append("\n##################\n")
    return message.toString()
  }

  private fun waitForActivityDestroy(): CountDownLatch {
    val latch = CountDownLatch(1)
    val countDownOnIdle = MessageQueue.IdleHandler {
      latch.countDown()
      false
    }
    val testActivity = activityRule.activity
    testActivity.application.registerActivityLifecycleCallbacks(
        object : ActivityLifecycleCallbacksAdapter() {
          override fun onActivityDestroyed(activity: Activity) {
            if (activity == testActivity) {
              activity.application.unregisterActivityLifecycleCallbacks(this)
              Looper.myQueue()
                  .addIdleHandler(countDownOnIdle)
            }
          }
        })
    return latch
  }

  companion object {
    private const val TOUCH_MODE = true
    private const val LAUNCH_ACTIVITY = true
  }
}
