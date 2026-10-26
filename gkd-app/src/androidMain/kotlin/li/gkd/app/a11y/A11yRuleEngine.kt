package li.gkd.app.a11y

import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import li.gkd.app.META
import li.gkd.app.data.ActionPerformer
import li.gkd.app.platform.lifecycle.MainActivityVisibility
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.rule.ActivityRule
import li.gkd.app.rule.AppRule
import li.gkd.app.rule.ResolvedRule
import li.gkd.app.rule.RuleDelayScheduler
import li.gkd.app.rule.RuleQueryQueue
import li.gkd.app.rule.RuleQueryRequest
import li.gkd.app.rule.RuleStatus
import li.gkd.app.service.EventService
import li.gkd.app.service.topAppIdFlow
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.util.AndroidTarget
import li.gkd.app.util.Constants
import li.gkd.app.util.AndroidToastUtils
import li.gkd.app.util.launchLogged
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

private val eventDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
private val queryDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
private val actionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

class A11yRuleEngine(private val service: A11yCommonImpl) {
    private val a11yContext = A11yContext(getRoot = { safeActiveWindow })
    private val effective get() = A11yRuntime.isEffective(service)
    private val hasOthersService = A11yRuntime.hasOtherService(service)

    fun onA11yConnected() {
        if (SettingsRepository.settings.value.enableBlockA11yAppList && !SettingsRepository.actualBlockA11yAppList.contains(
                topAppIdFlow.value
            )
        ) {
            startQueryJob(byForced = true)
        }
    }

    fun onScreenForcedActive() {
        // 关闭屏幕 -> Activity::onStop -> 点亮屏幕 -> Activity::onStart -> Activity::onResume
        A11yState.onScreenForcedActive()
        startQueryJob()
    }

    private fun readActiveWindow(): AccessibilityNodeInfo? = try {
            // 某些应用耗时 554ms
            // java.lang.SecurityException: Call from user 0 as user -2 without permission INTERACT_ACROSS_USERS or INTERACT_ACROSS_USERS_FULL not allowed.
            service.windowNodeInfo?.setGeneratedTime()
        } catch (_: Throwable) {
            null
        }

    val safeActiveWindow: AccessibilityNodeInfo?
        get() = readActiveWindow().apply {
            a11yContext.rootCache.value = this
        }

    private val safeActiveWindowAppId: String?
        get() = readActiveWindow()?.packageName?.toString()

    private val scope get() = service.scope

    @Volatile
    private var latestStateEvent: A11yEvent? = null
    private var lastContentEventTime = 0L
    private var lastEventTime = 0L
    private val eventQueue = A11yEventQueue()
    private val eventWakeup = Channel<Unit>(Channel.CONFLATED)
    private val eventConsumer by lazy {
        scope.launch(eventDispatcher) {
            for (signal in eventWakeup) {
                while (isActive) {
                    val events = eventQueue.takeBatch()
                    if (events.isEmpty()) break
                    launchLogged { consumeEvent(events) }.join()
                }
            }
        }
    }
    fun onA11yEvent(event: AccessibilityEvent?) {
        if (!effective) return
        if (!event.isUseful()) return
        // 拒绝副屏无障碍事件
        if (AndroidTarget.TIRAMISU && event.displayId != Display.DEFAULT_DISPLAY) return
        onA11yFeatEvent(event)
        if (event.eventType == CONTENT_CHANGED) {
            if (!isInteractive) return // 屏幕关闭后仍然有无障碍事件 type:2048, time:8094, app:com.miui.aod, cls:android.widget.TextView
            if (event.packageName == Constants.systemUiAppId && event.packageName != currentTopActivity.appId) return
        }
        // 过滤部分输入法事件
        if (event.packageName == imeAppId && currentTopActivity.appId != imeAppId) {
            if (event.recordCount == 0 && event.action == 0 && !event.isFullScreen) return
        }
        // 直接丢弃自身事件，自行更新 topActivity
        if (
            (event.eventType == CONTENT_CHANGED || !MainActivityVisibility.isVisible) &&
            event.packageName == META.appId
        ) return

        val a11yEvent = event.toA11yEvent() ?: return
        if (a11yEvent.type == CONTENT_CHANGED) {
            // 防止 content 类型事件过快
            if (a11yEvent.time - lastContentEventTime < 100 && a11yEvent.time - appChangeTime > 5000 && a11yEvent.time - lastTriggerTime > 3000) {
                return
            }
            lastContentEventTime = a11yEvent.time
        }
        EventService.logEvent(event)
        if (META.debuggable) {
            Log.d(
                "onNewA11yEvent",
                "type:${event.eventType}, time:${event.eventTime - lastEventTime}, app:${event.packageName}, cls:${event.className}"
            )
        }
        if (event.eventTime < lastEventTime) {
            // 某些应用会发送负时间事件, 直接丢弃
            // type:32, time:-104, app:com.miui.home, cls:com.miui.home.launcher.Launcher
            return
        }
        lastEventTime = event.eventTime
        if (event.eventType == STATE_CHANGED) {
            latestStateEvent = a11yEvent
        }
        eventQueue.add(a11yEvent)
        eventWakeup.trySend(Unit)
        eventConsumer.start()
    }

    private val queryEvents = mutableListOf<A11yEvent>()
    private suspend fun consumeEvent(consumedEvents: List<A11yEvent>) {
        val latestEvent = consumedEvents.last()
        val evAppId = latestEvent.appId
        val evActivityId = latestEvent.name
        val oldAppId = currentTopActivity.appId
        val rightAppId = if (oldAppId == evAppId) {
            evAppId
        } else {
            getTimeoutAppId() ?: return
        }
        if (rightAppId == evAppId) {
            if (latestEvent.type == STATE_CHANGED) {
                A11yState.withTopActivityLock {
                    // tv.danmaku.bili, com.miui.home, com.miui.home.launcher.Launcher
                    if (isActivity(evAppId, evActivityId)) {
                        updateTopActivity(evAppId, evActivityId)
                    }
                }
            }
        }
        if (rightAppId != currentTopActivity.appId) {
            A11yState.withTopActivityLock {
                // 从 锁屏，下拉通知栏 返回等情况, 应用不会发送事件, 但是系统组件会发送事件
                val topCpn = privilegeContextFlow.value?.topCpn()
                if (topCpn?.packageName == rightAppId) {
                    updateTopActivity(topCpn.packageName, topCpn.className)
                } else {
                    updateTopActivity(rightAppId, null)
                }
            }
        }
        val activityRule = activityRuleFlow.value
        if (evAppId != rightAppId || activityRule.skipConsumeEvent || !SettingsRepository.settings.value.enableMatch) {
            return
        }
        synchronized(queryEvents) { queryEvents.addAll(consumedEvents) }
        a11yContext.interruptKey++
        startQueryJob(byEvent = true)
    }

    private var lastGetAppIdTime = 0L
    private var lastAppId: String? = null
    private suspend fun getTimeoutAppId(): String? {
        if (lastAppId != null && System.currentTimeMillis() - lastGetAppIdTime <= 100) return lastAppId
        // 某些应用通过无障碍获取 safeActiveWindow 耗时长，导致多个事件连续堆积堵塞，无法检测到 appId 切换导致状态异常
        // https://github.com/gkd-kit/gkd/issues/622
        lastAppId = withTimeoutOrNull(100.milliseconds) {
            runInterruptible(Dispatchers.IO) { safeActiveWindowAppId }
        } ?: privilegeContextFlow.value?.run { topCpn()?.packageName }
        lastGetAppIdTime = System.currentTimeMillis()
        return lastAppId
    }

    private data class RootRequest(
        val activityRule: ActivityRule,
        val stateEvent: A11yEvent?,
        val result: Deferred<AccessibilityNodeInfo?>,
    )

    private var rootRequest: RootRequest? = null

    // A timed-out Binder read may keep running; reuse it instead of starting more reads.
    private suspend fun getTimeoutActiveWindow(): AccessibilityNodeInfo? {
        val activityRule = A11yState.currentRule
        val stateEvent = latestStateEvent
        val previous = rootRequest
        if (previous != null && checkOutDate(previous.activityRule, previous.stateEvent)) {
            if (!previous.result.isCompleted) return null
            rootRequest = null
        }
        val request = rootRequest ?: RootRequest(
            activityRule,
            stateEvent,
            scope.async(Dispatchers.IO) { readActiveWindow() },
        ).also { rootRequest = it }
        val node = withTimeoutOrNull(500.milliseconds) { request.result.await() }
        if (request.result.isCompleted) rootRequest = null
        if (!effective || checkOutDate(activityRule, stateEvent)) return null
        if (node != null) a11yContext.rootCache.value = node
        return node
    }

    private val queryQueue = RuleQueryQueue()
    private val queryConsumer by lazy {
        scope.launch(queryDispatcher) {
            while (isActive) {
                val request = queryQueue.next()
                launchLogged {
                    if (!effective || !SettingsRepository.settings.value.enableMatch) return@launchLogged
                    val st = if (META.debuggable) System.currentTimeMillis() else 0L
                    try {
                        queryAction(request.byEvent, request.byForced, request.delayRule)
                    } finally {
                        checkFutureStartJob()
                        if (META.debuggable) {
                            Log.d("A11yRuleEngine", "query end ${System.currentTimeMillis() - st} ms")
                        }
                    }
                }.join()
            }
        }
    }

    private fun startQueryJob(
        byEvent: Boolean = false,
        byForced: Boolean = false,
        byDelayRule: ResolvedRule? = null,
    ) {
        if (!effective) return
        if (!SettingsRepository.settings.value.enableMatch) return
        if (activityRuleFlow.value.currentRules.isEmpty()) return
        // 无障碍从零启动时获取 safeActiveWindow 非常耗时
        if (!byEvent && service.justStarted && !hasOthersService) return checkFutureStartJob()
        queryQueue.submit(RuleQueryRequest(byEvent, byForced, byDelayRule))
        queryConsumer.start()
    }

    private fun checkFutureStartJob() {
        val t = System.currentTimeMillis()
        if (t - lastTriggerTime < 3000L || t - appChangeTime < 3000L) {
            scheduleQuery()
        } else if (activityRuleFlow.value.hasFeatureAction) {
            scheduleQuery(byForced = true)
        }
    }

    private var futureQuery: Job? = null
    private var futureForcedOnly = false

    @Synchronized
    private fun scheduleQuery(byForced: Boolean = false) {
        if (futureQuery != null) {
            futureForcedOnly = futureForcedOnly && byForced
            return
        }
        futureForcedOnly = byForced
        val job = scope.launch(actionDispatcher, start = CoroutineStart.LAZY) {
            delay(300.milliseconds)
            val forced = synchronized(this@A11yRuleEngine) {
                futureQuery = null
                futureForcedOnly
            }
            startQueryJob(byForced = forced)
        }
        futureQuery = job
        job.start()
    }

    private fun fixAppId(rightAppId: String) {
        if (currentTopActivity.appId == rightAppId) return
        A11yState.withTopActivityLock {
            val topCpn = privilegeContextFlow.value?.topCpn()
            if (topCpn?.packageName == rightAppId) {
                updateTopActivity(topCpn.packageName, topCpn.className)
            } else {
                updateTopActivity(rightAppId, null)
            }
        }
        scheduleQuery()
    }

    private suspend fun queryAction(
        byEvent: Boolean = false,
        byForced: Boolean = false,
        delayRule: ResolvedRule? = null,
    ) {
        val tempStateEvent = latestStateEvent
        val newEvents = if (delayRule != null) {// 延迟规则不消耗事件
            null
        } else {
            synchronized(queryEvents) {
                if (byEvent && queryEvents.isEmpty()) {
                    return
                }
                (if (queryEvents.size > 1) {
                    val first = queryEvents.first()
                    val hasDiffItem = queryEvents.any { !it.sameAs(first) }
                    if (hasDiffItem) {
                        // 存在不同的事件节点, 全部丢弃使用 root 查询
                        null
                    } else {
                        // type,appId,className 一致, 需要在 synchronized 外验证是否是同一节点
                        arrayOf(
                            queryEvents[queryEvents.size - 2],
                            queryEvents.last(),
                        )
                    }
                } else if (queryEvents.size == 1) {
                    arrayOf(queryEvents.last())
                } else {
                    null
                }).apply {
                    queryEvents.clear()
                }
            }
        }
        val activityRule = A11yState.currentRule
        activityRule.currentRules.forEach { rule ->
            if (rule.status == RuleStatus.MatchDelay) {
                RuleExecutionHost.delays.schedule(
                    rule,
                    RuleDelayScheduler.Kind.Match,
                    scope,
                    actionDispatcher,
                    rule.matchDelay
                ) {
                    startQueryJob(byDelayRule = rule)
                }
            }
        }
        if (activityRule.skipMatch) {
            // 如果当前应用没有规则/暂停匹配, 则不去调用获取事件节点避免阻塞
            return
        }
        var lastNode = if (newEvents == null || newEvents.size <= 1) {
            newEvents?.firstOrNull()?.safeSource
        } else {
            // 获取最后两个事件, 如果最后两个事件的节点不一致, 则丢弃
            // 相等则是同一个节点发出的连续事件, 常见于倒计时界面
            val lastNode = newEvents.last().safeSource
            if (lastNode == null || lastNode == newEvents[0].safeSource) {
                lastNode
            } else {
                null
            }
        }
        var lastNodeUsed = false
        if (!a11yContext.clearOldAppNodeCache()) {
            if (byEvent) { // 此为多数情况
                // 新事件到来时, 若缓存清理不及时会导致无法查询到节点
                a11yContext.clearNodeCache(lastNode)
            }
        }
        var rootNode: AccessibilityNodeInfo? = null
        var rootLoaded = false
        var rootGeneration = a11yContext.interruptKey
        for (rule in activityRule.priorityRules) { // 规则数量有可能过多导致耗时过长
            if (!effective) return
            if (checkOutDate(activityRule, tempStateEvent)) break
            if (delayRule != null && delayRule !== rule) continue
            if (rule.status != RuleStatus.Ready) continue
            if (byForced && !rule.checkForced()) continue
            lastNode?.let { n ->
                val refreshOk = (!lastNodeUsed) || (try {
                    val e = n.refresh()
                    if (e) {
                        n.setGeneratedTime()
                    }
                    e
                } catch (_: Throwable) {
                    false
                })
                lastNodeUsed = true
                if (!refreshOk) {
                    lastNode = null
                }
            }
            if (rootGeneration != a11yContext.interruptKey || rootNode?.isExpired(1000L) == true) {
                rootGeneration = a11yContext.interruptKey
                rootNode = null
                rootLoaded = false
            }
            val nodeVal = lastNode ?: run {
                if (!rootLoaded) {
                    rootNode = getTimeoutActiveWindow()
                    rootLoaded = true
                }
                rootNode
            } ?: break
            val rightAppId = nodeVal.packageName?.toString() ?: break
            val matchApp = rule.matchActivity(rightAppId)
            if (currentTopActivity.appId != rightAppId || (!matchApp && rule is AppRule)) {
                scope.launch(eventDispatcher) { fixAppId(rightAppId) }
                return
            }
            if (!matchApp) continue
            val target = a11yContext.queryRule(rule, nodeVal) ?: continue
            if (rule.checkDelay()) {
                RuleExecutionHost.delays.schedule(
                    rule,
                    RuleDelayScheduler.Kind.Action,
                    scope,
                    actionDispatcher,
                    rule.actionDelay
                ) {
                    startQueryJob(byDelayRule = rule)
                }
                continue
            }
            if (rule.status != RuleStatus.Ready) break
            if (checkOutDate(activityRule, tempStateEvent)) break
            val performer = ActionPerformer.getAction(
                rule.rule.action
                ?: rule.rule.position?.let { ActionPerformer.ClickCenter.action }
                ?: rule.rule.swipeArg?.let { ActionPerformer.Swipe.action })
            val actionResult = performer.perform(target, rule.rule)
            // Even an unsuccessful action may have changed the target application's UI.
            rootNode = null
            rootLoaded = false
            if (actionResult.result) {
                val topActivity = currentTopActivity
                rule.trigger()
                SettingsRepository.incrementActionCount()
                scheduleQuery()
                if (actionResult.action != ActionPerformer.None.action) {
                    AndroidToastUtils.showAction(rule)
                }
                addActionLog(rule, topActivity, target, actionResult)
            }
        }
    }

    private fun checkOutDate(
        activityRule: ActivityRule,
        stateEvent: A11yEvent?
    ): Boolean {
        if (stateEvent !== latestStateEvent) return true
        return activityRule !== A11yState.currentRule
    }

}
