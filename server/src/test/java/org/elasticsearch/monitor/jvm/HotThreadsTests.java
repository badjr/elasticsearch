/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.monitor.jvm;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.mockito.InOrder;
import org.mockito.Matchers;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HotThreadsTests extends ESTestCase {

    private long nanosHelper(long millis) {
        return millis * 1_000_000;
    }

    public void testSupportedThreadsReportType() {
        for (String type : new String[]{"unsupported", "", null, "CPU", "WAIT", "BLOCK", "MEM"}) {
            expectThrows(IllegalArgumentException.class, () -> new HotThreads().type(HotThreads.ReportType.of(type)));
        }

        for (String type : new String[]{"cpu", "wait", "block", "mem"}) {
            try {
                new HotThreads().type(HotThreads.ReportType.of(type));
            } catch (IllegalArgumentException e) {
                fail(String.format(Locale.ROOT, "IllegalArgumentException called when creating HotThreads for supported type [%s]", type));
            }
        }
    }

    private void assertIdleThreadHelper(ThreadInfo threadInfo, List<StackTraceElement> stack) {
        when(threadInfo.getStackTrace()).thenReturn(stack.toArray(new StackTraceElement[0]));
        assertTrue(HotThreads.isIdleThread(threadInfo));
    }

    private List<StackTraceElement> makeThreadStackHelper(List<String[]> names) {
        return names.stream().map(e -> {
            // Cannot mock StackTraceElement because it's final
            return new StackTraceElement(e[0], e[1], "Some_File", 1);
        }).collect(Collectors.toList());
    }

    public void testIdleThreadsDetection() {
        for (String threadName : new String[]{
            "Signal Dispatcher", "Finalizer", "Reference Handler", "Notification Thread",
            "Common-Cleaner", "process reaper", "DestroyJavaVM"}) {
            ThreadInfo mockedThreadInfo = mock(ThreadInfo.class);
            when(mockedThreadInfo.getThreadName()).thenReturn(threadName);
            assertTrue(HotThreads.isKnownJDKThread(mockedThreadInfo));
            assertTrue(HotThreads.isIdleThread(mockedThreadInfo));
        }

        for (String threadName : new String[]{"Text", "", null, "Finalizer".toLowerCase(Locale.ROOT)}) {
            ThreadInfo mockedThreadInfo = mock(ThreadInfo.class);
            when(mockedThreadInfo.getThreadName()).thenReturn(threadName);
            when(mockedThreadInfo.getStackTrace()).thenReturn(new StackTraceElement[0]);
            assertFalse(HotThreads.isKnownJDKThread(mockedThreadInfo));
            assertFalse(HotThreads.isIdleThread(mockedThreadInfo));
        }

        List<StackTraceElement> testJvmStack = makeThreadStackHelper(
            List.of(
                new String[]{"org.elasticsearch.monitor.test", "methodOne"},
                new String[]{"org.elasticsearch.monitor.testOther", "methodTwo"},
                new String[]{"org.elasticsearch.monitor.test", "methodThree"},
                new String[]{"org.elasticsearch.monitor.testOther", "methodFour"}
            ));

        for (StackTraceElement stackFrame : testJvmStack) {
            assertFalse(HotThreads.isKnownIdleStackFrame(stackFrame.getClassName(), stackFrame.getMethodName()));
        }

        ThreadInfo notIdleThread = mock(ThreadInfo.class);
        when(notIdleThread.getThreadName()).thenReturn("Not Idle Thread");
        when(notIdleThread.getStackTrace()).thenReturn(testJvmStack.toArray(new StackTraceElement[0]));

        assertFalse(HotThreads.isIdleThread(notIdleThread));

        List<StackTraceElement> idleThreadStackElements = makeThreadStackHelper(
            List.of(
                new String[]{"java.util.concurrent.ThreadPoolExecutor", "getTask"},
                new String[]{"sun.nio.ch.SelectorImpl", "select"},
                new String[]{"org.elasticsearch.threadpool.ThreadPool$CachedTimeThread", "run"},
                new String[]{"org.elasticsearch.indices.ttl.IndicesTTLService$Notifier", "await"},
                new String[]{"java.util.concurrent.LinkedTransferQueue", "poll"},
                new String[]{"com.sun.jmx.remote.internal.ServerCommunicatorAdmin$Timeout", "run"}
            ));

        for (StackTraceElement extraFrame : idleThreadStackElements) {
            ThreadInfo idleThread = mock(ThreadInfo.class);
            when(idleThread.getThreadName()).thenReturn("Idle Thread");
            when(idleThread.getStackTrace()).thenReturn(new StackTraceElement[]{extraFrame});
            assertTrue(HotThreads.isKnownIdleStackFrame(extraFrame.getClassName(), extraFrame.getMethodName()));
            assertTrue(HotThreads.isIdleThread(idleThread));

            List<StackTraceElement> topOfStack = new ArrayList<>(testJvmStack);
            topOfStack.add(0, extraFrame);
            assertIdleThreadHelper(idleThread, topOfStack);

            List<StackTraceElement> bottomOfStack = new ArrayList<>(testJvmStack);
            bottomOfStack.add(extraFrame);
            assertIdleThreadHelper(idleThread, bottomOfStack);

            if (testJvmStack.size() > 1) {
                List<StackTraceElement> middleOfStack = new ArrayList<>(testJvmStack);
                middleOfStack.add(between(Math.min(1, testJvmStack.size()), Math.max(0, testJvmStack.size() - 1)), extraFrame);
                assertIdleThreadHelper(idleThread, middleOfStack);
            }
        }
    }

    public void testSimilarity() {
        StackTraceElement[] stackOne = makeThreadStackHelper(
            List.of(
                new String[]{"org.elasticsearch.monitor.test", "methodOne"},
                new String[]{"org.elasticsearch.monitor.testOther", "methodTwo"}
            )).toArray(new StackTraceElement[0]);

        StackTraceElement[] stackTwo = makeThreadStackHelper(
            List.of(
                new String[]{"org.elasticsearch.monitor.test1", "methodOne"},
                new String[]{"org.elasticsearch.monitor.testOther", "methodTwo"}
            )).toArray(new StackTraceElement[0]);

        StackTraceElement[] stackThree = makeThreadStackHelper(
            List.of(
                new String[]{"org.elasticsearch.monitor.testOther", "methodTwo"},
                new String[]{"org.elasticsearch.monitor.test", "methodOne"}
            )).toArray(new StackTraceElement[0]);

        StackTraceElement[] stackFour = makeThreadStackHelper(
            List.of(
                new String[]{"org.elasticsearch.monitor.testPrior", "methodOther"},
                new String[]{"org.elasticsearch.monitor.test", "methodOne"},
                new String[]{"org.elasticsearch.monitor.testOther", "methodTwo"}
            )).toArray(new StackTraceElement[0]);

        HotThreads hotThreads = new HotThreads();

        // We can simplify this with records when the toolchain is upgraded
        class SimilarityTestCase {
            final StackTraceElement[] one;
            final StackTraceElement[] two;
            final int similarityScore;

            SimilarityTestCase(StackTraceElement[] one, StackTraceElement[] two, int similarityScore) {
                this.one = one;
                this.two = two;
                this.similarityScore = similarityScore;
            }
        }

        SimilarityTestCase[] testCases = new SimilarityTestCase[]{
            new SimilarityTestCase(stackOne, stackOne, 2),
            new SimilarityTestCase(stackOne, stackTwo, 1),
            new SimilarityTestCase(stackOne, stackThree, 0),
            new SimilarityTestCase(stackOne, stackFour, 2),
            new SimilarityTestCase(stackTwo, stackFour, 1),
            new SimilarityTestCase(stackOne, new StackTraceElement[0], 0),
        };

        for (SimilarityTestCase testCase : testCases) {
            ThreadInfo threadOne = mock(ThreadInfo.class);
            when(threadOne.getThreadName()).thenReturn("Thread One");
            when(threadOne.getStackTrace()).thenReturn(testCase.one);

            ThreadInfo threadTwo = mock(ThreadInfo.class);
            when(threadTwo.getThreadName()).thenReturn("Thread Two");
            when(threadTwo.getStackTrace()).thenReturn(testCase.two);

            assertEquals(testCase.similarityScore, hotThreads.similarity(threadOne, threadTwo));
        }

        ThreadInfo threadOne = mock(ThreadInfo.class);
        when(threadOne.getThreadName()).thenReturn("Thread One");
        when(threadOne.getStackTrace()).thenReturn(testCases[0].one);

        assertEquals(0, hotThreads.similarity(threadOne, null));
    }

    private ThreadInfo makeThreadInfoMocksHelper(ThreadMXBean mockedMXBean, long threadId) {
        return makeThreadInfoMocksHelper(mockedMXBean, threadId, 1L);
    }

    private ThreadInfo makeThreadInfoMocksHelper(ThreadMXBean mockedMXBean, long threadId, long cpuMultiplier) {
        when(mockedMXBean.getThreadCpuTime(threadId)).thenReturn(0L).thenReturn(threadId * cpuMultiplier);
        ThreadInfo mockedThreadInfo = mock(ThreadInfo.class);
        when(mockedMXBean.getThreadInfo(eq(threadId), anyInt())).thenReturn(mockedThreadInfo);
        when(mockedThreadInfo.getThreadName()).thenReturn(String.format(Locale.ROOT, "Thread %d", threadId));

        // We create some variability for the blocked and waited times. Odd and even.
        when(mockedThreadInfo.getBlockedCount()).thenReturn(0L).thenReturn(threadId % 2);
        long blockedTime = ((threadId % 2) == 0) ? 0L : threadId;
        when(mockedThreadInfo.getBlockedTime()).thenReturn(0L).thenReturn(blockedTime);

        when(mockedThreadInfo.getWaitedCount()).thenReturn(0L).thenReturn((threadId + 1) % 2);
        long waitTime = (((threadId + 1) % 2) == 0) ? 0L : threadId;
        when(mockedThreadInfo.getWaitedTime()).thenReturn(0L).thenReturn(waitTime);

        when(mockedThreadInfo.getThreadId()).thenReturn(threadId);

        StackTraceElement[] stack = makeThreadStackHelper(
            List.of(
                new String[]{"org.elasticsearch.monitor.test", String.format(Locale.ROOT, "method_%d", (threadId) % 2)},
                new String[]{"org.elasticsearch.monitor.testOther", "methodFinal"}
            )).toArray(new StackTraceElement[0]);
        when(mockedThreadInfo.getStackTrace()).thenReturn(stack);

        return mockedThreadInfo;
    }

    private List<ThreadInfo> makeThreadInfoMocksHelper(ThreadMXBean mockedMXBean, long[] threadIds) {
        return makeThreadInfoMocksHelper(mockedMXBean, threadIds, 1L);
    }

    // We call this helper for each different mode to reset the before and after timings.
    private List<ThreadInfo> makeThreadInfoMocksHelper(ThreadMXBean mockedMXBean, long[] threadIds, long cpuMultiplier) {
        List<ThreadInfo> allInfos = new ArrayList<>(threadIds.length);

        for (long threadId : threadIds) {
            allInfos.add(makeThreadInfoMocksHelper(mockedMXBean, threadId, cpuMultiplier));
        }

        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(allInfos.toArray(new ThreadInfo[0]));
        when(mockedMXBean.getThreadInfo(Matchers.any(long[].class))).thenReturn(allInfos.toArray(new ThreadInfo[0]));

        return allInfos;
    }

    private ThreadMXBean makeMockMXBeanHelper() {
        ThreadMXBean mockedMXBean = mock(ThreadMXBean.class);
        when(mockedMXBean.isThreadCpuTimeSupported()).thenReturn(true);
        when(mockedMXBean.isThreadContentionMonitoringSupported()).thenReturn(true);
        when(mockedMXBean.isThreadContentionMonitoringEnabled()).thenReturn(true);

        return mockedMXBean;
    }

    private SunThreadInfo makeMockSunThreadInfoHelper() {
        SunThreadInfo mockedSunThreadInfo = mock(SunThreadInfo.class);
        when(mockedSunThreadInfo.isThreadAllocatedMemorySupported()).thenReturn(true);
        when(mockedSunThreadInfo.isThreadAllocatedMemoryEnabled()).thenReturn(true);

        return mockedSunThreadInfo;
    }

    public void testInnerDetectCPUMode() throws Exception {
        ThreadMXBean mockedMXBean = makeMockMXBeanHelper();
        SunThreadInfo mockedSunThreadInfo = makeMockSunThreadInfoHelper();

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        long mockCurrentThreadId = 0L;
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds, 100_000);
        List<ThreadInfo> cpuOrderedInfos = List.of(allInfos.get(0), allInfos.get(1), allInfos.get(2), allInfos.get(3));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(cpuOrderedInfos.toArray(new ThreadInfo[0]));

        HotThreads hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.CPU)
            .interval(TimeValue.timeValueMillis(10))
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        String innerResult = hotThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);

        assertThat(innerResult, containsString("Hot threads at "));
        assertThat(innerResult, containsString("interval=10ms, busiestThreads=4, ignoreIdleThreads=false:"));
        assertThat(innerResult, containsString("11/11 snapshots sharing following 2 elements"));
        assertThat(innerResult, stringContainsInOrder(
            "90.0% [cpu=1.0%, other=89.0%] (9ms out of 10ms) cpu usage by thread 'Thread 1'",
            "80.0% [cpu=2.0%, other=78.0%] (8ms out of 10ms) cpu usage by thread 'Thread 2'",
            "70.0% [cpu=3.0%, other=67.0%] (7ms out of 10ms) cpu usage by thread 'Thread 3'",
            "60.0% [cpu=4.0%, other=56.0%] (6ms out of 10ms) cpu usage by thread 'Thread 4'"));
        assertThat(innerResult, containsString("org.elasticsearch.monitor.test.method_0(Some_File:1)"));
        assertThat(innerResult, containsString("org.elasticsearch.monitor.test.method_1(Some_File:1)"));
        assertThat(innerResult, containsString("org.elasticsearch.monitor.testOther.methodFinal(Some_File:1)"));


        // Let's ask again without progressing the CPU thread counters, e.g. resetting the mocks
        innerResult = hotThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);

        assertThat(innerResult, containsString("0.0% [cpu=0.0%, other=0.0%] (0s out of 10ms) cpu usage by thread 'Thread 4'"));
        assertThat(innerResult, containsString("0.0% [cpu=0.0%, other=0.0%] (0s out of 10ms) cpu usage by thread 'Thread 3'"));
        assertThat(innerResult, containsString("0.0% [cpu=0.0%, other=0.0%] (0s out of 10ms) cpu usage by thread 'Thread 2'"));
        assertThat(innerResult, containsString("0.0% [cpu=0.0%, other=0.0%] (0s out of 10ms) cpu usage by thread 'Thread 1'"));

        // Test with the legacy sort order
        allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds, 100_000);
        cpuOrderedInfos = List.of(allInfos.get(3), allInfos.get(2), allInfos.get(1), allInfos.get(0));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(cpuOrderedInfos.toArray(new ThreadInfo[0]));

        hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.CPU)
            .interval(TimeValue.timeValueMillis(10))
            .sortOrder(HotThreads.SortOrder.CPU)
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        innerResult = hotThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);

        assertThat(innerResult, containsString("Hot threads at "));
        assertThat(innerResult, containsString("interval=10ms, busiestThreads=4, ignoreIdleThreads=false:"));
        assertThat(innerResult, containsString("11/11 snapshots sharing following 2 elements"));
        assertThat(innerResult, stringContainsInOrder(
            "60.0% [cpu=4.0%, other=56.0%] (6ms out of 10ms) cpu usage by thread 'Thread 4'",
            "70.0% [cpu=3.0%, other=67.0%] (7ms out of 10ms) cpu usage by thread 'Thread 3'",
            "80.0% [cpu=2.0%, other=78.0%] (8ms out of 10ms) cpu usage by thread 'Thread 2'",
            "90.0% [cpu=1.0%, other=89.0%] (9ms out of 10ms) cpu usage by thread 'Thread 1'"));
        assertThat(innerResult, containsString("org.elasticsearch.monitor.test.method_0(Some_File:1)"));
        assertThat(innerResult, containsString("org.elasticsearch.monitor.test.method_1(Some_File:1)"));
        assertThat(innerResult, containsString("org.elasticsearch.monitor.testOther.methodFinal(Some_File:1)"));
    }

    public void testInnerDetectWaitMode() throws Exception {
        ThreadMXBean mockedMXBean = makeMockMXBeanHelper();
        SunThreadInfo mockedSunThreadInfo = makeMockSunThreadInfoHelper();

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        long mockCurrentThreadId = 0L;
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        HotThreads hotWaitingThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.WAIT)
            .interval(TimeValue.timeValueMillis(50))
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        List<ThreadInfo> waitOrderedInfos = List.of(allInfos.get(3), allInfos.get(1), allInfos.get(0), allInfos.get(2));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(waitOrderedInfos.toArray(new ThreadInfo[0]));

        String waitInnerResult = hotWaitingThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);

        assertThat(waitInnerResult, stringContainsInOrder(
            "8.0% (4ms out of 50ms) wait usage by thread 'Thread 4'",
            "4.0% (2ms out of 50ms) wait usage by thread 'Thread 2'",
            "0.0% (0s out of 50ms) wait usage by thread 'Thread 1'",
            "0.0% (0s out of 50ms) wait usage by thread 'Thread 3'"));

        // Sort order has no impact on wait mode
        hotWaitingThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.WAIT)
            .sortOrder(HotThreads.SortOrder.CPU)
            .interval(TimeValue.timeValueMillis(50))
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        waitOrderedInfos = List.of(allInfos.get(3), allInfos.get(1), allInfos.get(0), allInfos.get(2));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(waitOrderedInfos.toArray(new ThreadInfo[0]));

        waitInnerResult = hotWaitingThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);

        assertThat(waitInnerResult, stringContainsInOrder(
            "8.0% (4ms out of 50ms) wait usage by thread 'Thread 4'",
            "4.0% (2ms out of 50ms) wait usage by thread 'Thread 2'",
            "0.0% (0s out of 50ms) wait usage by thread 'Thread 1'",
            "0.0% (0s out of 50ms) wait usage by thread 'Thread 3'"));
    }

    public void testInnerDetectBlockedMode() throws Exception {
        ThreadMXBean mockedMXBean = makeMockMXBeanHelper();
        SunThreadInfo mockedSunThreadInfo = makeMockSunThreadInfoHelper();

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        long mockCurrentThreadId = 0L;
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);
        HotThreads hotBlockedThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.BLOCK)
            .interval(TimeValue.timeValueMillis(60))
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        List<ThreadInfo> blockOrderedInfos = List.of(allInfos.get(2), allInfos.get(0), allInfos.get(1), allInfos.get(3));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(blockOrderedInfos.toArray(new ThreadInfo[0]));

        String blockInnerResult = hotBlockedThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);

        assertThat(blockInnerResult, stringContainsInOrder(
            "5.0% (3ms out of 60ms) block usage by thread 'Thread 3'",
            "1.7% (1ms out of 60ms) block usage by thread 'Thread 1'",
            "0.0% (0s out of 60ms) block usage by thread 'Thread 2'",
            "0.0% (0s out of 60ms) block usage by thread 'Thread 4'"));

        // Sort order has no impact on block mode
        hotBlockedThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.BLOCK)
            .sortOrder(HotThreads.SortOrder.CPU)
            .interval(TimeValue.timeValueMillis(60))
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        blockOrderedInfos = List.of(allInfos.get(2), allInfos.get(0), allInfos.get(1), allInfos.get(3));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(blockOrderedInfos.toArray(new ThreadInfo[0]));

        blockInnerResult = hotBlockedThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);

        assertThat(blockInnerResult, stringContainsInOrder(
            "5.0% (3ms out of 60ms) block usage by thread 'Thread 3'",
            "1.7% (1ms out of 60ms) block usage by thread 'Thread 1'",
            "0.0% (0s out of 60ms) block usage by thread 'Thread 2'",
            "0.0% (0s out of 60ms) block usage by thread 'Thread 4'"));
    }

    public void testInnerDetectMemoryMode() throws Exception {
        ThreadMXBean mockedMXBean = makeMockMXBeanHelper();
        SunThreadInfo mockedSunThreadInfo = makeMockSunThreadInfoHelper();

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        long mockCurrentThreadId = 0L;
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        List<ThreadInfo> cpuOrderedInfos = List.of(allInfos.get(3), allInfos.get(2), allInfos.get(1), allInfos.get(0));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(cpuOrderedInfos.toArray(new ThreadInfo[0]));

        for (long threadId : threadIds) {
            when(mockedSunThreadInfo.getThreadAllocatedBytes(threadId)).thenReturn(0L).thenReturn(threadId*100);
        }

        HotThreads hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.MEM)
            .interval(TimeValue.timeValueNanos(10))
            .threadElementsSnapshotCount(1)
            .ignoreIdleThreads(false);

        String memInnerResult = hotThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);
        assertThat(memInnerResult, containsString("  unique snapshot"));
        assertThat(memInnerResult, stringContainsInOrder(
            "400b memory allocated by thread 'Thread 4'",
            "300b memory allocated by thread 'Thread 3'",
            "200b memory allocated by thread 'Thread 2'",
            "100b memory allocated by thread 'Thread 1'"));

        // Sort order has no impact on memory mode

        allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        cpuOrderedInfos = List.of(allInfos.get(3), allInfos.get(2), allInfos.get(1), allInfos.get(0));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(cpuOrderedInfos.toArray(new ThreadInfo[0]));

        for (long threadId : threadIds) {
            when(mockedSunThreadInfo.getThreadAllocatedBytes(threadId)).thenReturn(0L).thenReturn(threadId*100);
        }

        hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.MEM)
            .sortOrder(HotThreads.SortOrder.CPU)
            .interval(TimeValue.timeValueNanos(10))
            .threadElementsSnapshotCount(1)
            .ignoreIdleThreads(false);

        memInnerResult = hotThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);
        assertThat(memInnerResult, containsString("  unique snapshot"));
        assertThat(memInnerResult, stringContainsInOrder(
            "400b memory allocated by thread 'Thread 4'",
            "300b memory allocated by thread 'Thread 3'",
            "200b memory allocated by thread 'Thread 2'",
            "100b memory allocated by thread 'Thread 1'"));
    }

    public void testInnerDetectSingleSnapshot() throws Exception {
        ThreadMXBean mockedMXBean = makeMockMXBeanHelper();
        SunThreadInfo mockedSunThreadInfo = makeMockSunThreadInfoHelper();

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        long mockCurrentThreadId = 0L;
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        // Test with only one stack to trigger the different print in innerDetect
        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        List<ThreadInfo> cpuOrderedInfos = List.of(allInfos.get(3), allInfos.get(2), allInfos.get(1), allInfos.get(0));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(cpuOrderedInfos.toArray(new ThreadInfo[0]));

        HotThreads hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.CPU)
            .interval(TimeValue.timeValueNanos(10))
            .threadElementsSnapshotCount(1)
            .ignoreIdleThreads(false);

        String singleResult = hotThreads.innerDetect(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId, (interval) -> null);

        assertThat(singleResult, containsString("  unique snapshot"));
        assertEquals(5, singleResult.split(" unique snapshot").length);
        assertThat(singleResult, containsString("40.0% [cpu=40.0%, other=0.0%] (4nanos out of 10nanos) cpu usage by thread 'Thread 4'"));
        assertThat(singleResult, containsString("30.0% [cpu=30.0%, other=0.0%] (3nanos out of 10nanos) cpu usage by thread 'Thread 3'"));
        assertThat(singleResult, containsString("20.0% [cpu=20.0%, other=0.0%] (2nanos out of 10nanos) cpu usage by thread 'Thread 2'"));
        assertThat(singleResult, containsString("10.0% [cpu=10.0%, other=0.0%] (1nanos out of 10nanos) cpu usage by thread 'Thread 1'"));
        assertThat(singleResult, containsString("org.elasticsearch.monitor.test.method_0(Some_File:1)"));
        assertThat(singleResult, containsString("org.elasticsearch.monitor.test.method_1(Some_File:1)"));
        assertThat(singleResult, containsString("org.elasticsearch.monitor.testOther.methodFinal(Some_File:1)"));
    }

    public void testEnsureInnerDetectSkipsCurrentThread() throws Exception {
        ThreadMXBean mockedMXBean = makeMockMXBeanHelper();

        long mockCurrentThreadId = 5L;
        long[] threadIds = new long[]{mockCurrentThreadId}; // Matches half the intervalNanos for calculating time percentages

        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(allInfos.toArray(new ThreadInfo[0]));

        HotThreads hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.CPU)
            .interval(TimeValue.timeValueNanos(10))
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        String innerResult = hotThreads.innerDetect(mockedMXBean, mock(SunThreadInfo.class), mockCurrentThreadId, (interval) -> null);

        assertEquals(1, innerResult.lines().count());
    }

    public void testReportTypeValueGetter() {
        ThreadInfo mockedThreadInfo = mock(ThreadInfo.class);

        when(mockedThreadInfo.getBlockedTime()).thenReturn(2L).thenReturn(0L);
        when(mockedThreadInfo.getWaitedTime()).thenReturn(3L).thenReturn(0L);

        HotThreads.ThreadTimeAccumulator info = new HotThreads.ThreadTimeAccumulator(
            mockedThreadInfo, new TimeValue(10L), 1L, 4L);

        assertEquals(1L, HotThreads.ThreadTimeAccumulator.valueGetterForReportType(HotThreads.ReportType.CPU).applyAsLong(info));
        assertEquals(nanosHelper(3L),
            HotThreads.ThreadTimeAccumulator.valueGetterForReportType(HotThreads.ReportType.WAIT).applyAsLong(info));
        assertEquals(nanosHelper(2L),
            HotThreads.ThreadTimeAccumulator.valueGetterForReportType(HotThreads.ReportType.BLOCK).applyAsLong(info));
        assertEquals(4L, HotThreads.ThreadTimeAccumulator.valueGetterForReportType(HotThreads.ReportType.MEM).applyAsLong(info));

        //Ensure all enum types have a report type getter
        for (HotThreads.ReportType type : HotThreads.ReportType.values()) {
            assertNotNull(HotThreads.ThreadTimeAccumulator.valueGetterForReportType(type));
        }
    }

    public void testGetAllValidThreadInfos() {
        ThreadMXBean mockedMXBean = mock(ThreadMXBean.class);
        when(mockedMXBean.isThreadCpuTimeSupported()).thenReturn(true);
        SunThreadInfo mockedSunThreadInfo = mock(SunThreadInfo.class);

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        long mockCurrentThreadId = 0L;
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        HotThreads hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.CPU)
            .interval(TimeValue.timeValueNanos(10))
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        // Test the case when all threads exist before and after sleep
        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);

        Map<Long, HotThreads.ThreadTimeAccumulator> validInfos = hotThreads.getAllValidThreadInfos(mockedMXBean, mockedSunThreadInfo,
            mockCurrentThreadId);
        assertEquals(allInfos.size(), validInfos.size());

        for (long threadId : threadIds) {
            HotThreads.ThreadTimeAccumulator accumulator = validInfos.get(threadId);
            assertNotNull(accumulator);
            assertEquals(0, accumulator.getCpuTime());
            assertEquals(0, accumulator.getBlockedTime());
            assertEquals(0, accumulator.getWaitedTime());
        }

        // Fake sleep, e.g. don't sleep call the mock again

        Map<Long, HotThreads.ThreadTimeAccumulator> afterValidInfos = hotThreads.getAllValidThreadInfos(mockedMXBean, mockedSunThreadInfo,
            mockCurrentThreadId);
        assertEquals(allInfos.size(), afterValidInfos.size());
        for (long threadId : threadIds) {
            HotThreads.ThreadTimeAccumulator accumulator = afterValidInfos.get(threadId);
            assertNotNull(accumulator);
            boolean evenThreadId = ((threadId % 2) == 0);

            assertEquals(threadId, accumulator.getCpuTime());
            assertEquals(nanosHelper((evenThreadId) ? 0 : threadId), accumulator.getBlockedTime());
            assertEquals(nanosHelper((evenThreadId) ? threadId : 0), accumulator.getWaitedTime());
        }

        // Test when a thread has terminated during sleep, we don't report that thread
        allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);

        validInfos = hotThreads.getAllValidThreadInfos(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId);
        assertEquals(allInfos.size(), validInfos.size());

        ThreadInfo removedInfo = allInfos.remove(0);
        long[] reducedThreadIds = new long[]{2, 3, 4};
        when(mockedMXBean.getAllThreadIds()).thenReturn(reducedThreadIds);
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(allInfos.toArray(new ThreadInfo[0]));
        when(mockedMXBean.getThreadInfo(Matchers.any(long[].class))).thenReturn(allInfos.toArray(new ThreadInfo[0]));

        // Fake sleep

        afterValidInfos = hotThreads.getAllValidThreadInfos(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId);
        assertEquals(reducedThreadIds.length, afterValidInfos.size());

        for (long threadId : reducedThreadIds) {
            HotThreads.ThreadTimeAccumulator accumulator = afterValidInfos.get(threadId);
            assertNotNull(accumulator);
            boolean evenThreadId = ((threadId % 2) == 0);

            assertEquals(threadId, accumulator.getCpuTime());
            assertEquals(nanosHelper((evenThreadId) ? 0 : threadId), accumulator.getBlockedTime());
            assertEquals(nanosHelper((evenThreadId) ? threadId : 0), accumulator.getWaitedTime());
        }

        // Test capturing timings for thread that launched while we were sleeping
        allInfos.add(0, removedInfo); // We called getInfo on this once, so second time should be with cpu/wait > 0
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(allInfos.toArray(new ThreadInfo[0]));
        when(mockedMXBean.getThreadInfo(Matchers.any(long[].class))).thenReturn(allInfos.toArray(new ThreadInfo[0]));

        // Fake sleep

        afterValidInfos = hotThreads.getAllValidThreadInfos(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId);
        assertEquals(threadIds.length, afterValidInfos.size());

        HotThreads.ThreadTimeAccumulator firstAccumulator = afterValidInfos.get(removedInfo.getThreadId());
        assertEquals(1, firstAccumulator.getCpuTime());
        assertEquals(nanosHelper(0), firstAccumulator.getWaitedTime());
        assertEquals(nanosHelper(1), firstAccumulator.getBlockedTime());

        // Test skipping of current thread
        validInfos = hotThreads.getAllValidThreadInfos(mockedMXBean, mockedSunThreadInfo, threadIds[threadIds.length - 1]);
        assertEquals(threadIds.length - 1, validInfos.size());
        assertFalse(validInfos.containsKey(threadIds[threadIds.length - 1]));

        // Test skipping threads with CPU time of -1
        when(mockedMXBean.getThreadCpuTime(threadIds[0])).thenReturn(-1L);
        validInfos = hotThreads.getAllValidThreadInfos(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId);
        assertEquals(threadIds.length - 1, validInfos.size());
        assertFalse(validInfos.containsKey(threadIds[0]));

        // Test skipping null thread infos
        when(mockedMXBean.getThreadInfo(eq(threadIds[0]), anyInt())).thenReturn(null);
        validInfos = hotThreads.getAllValidThreadInfos(mockedMXBean, mockedSunThreadInfo, mockCurrentThreadId);
        assertEquals(threadIds.length - 1, validInfos.size());
        assertFalse(validInfos.containsKey(threadIds[0]));
    }

    public void testCaptureThreadStacks() throws InterruptedException {
        ThreadMXBean mockedMXBean = mock(ThreadMXBean.class);
        when(mockedMXBean.isThreadCpuTimeSupported()).thenReturn(true);

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        HotThreads hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.CPU)
            .interval(TimeValue.timeValueNanos(1))
            .threadElementsSnapshotCount(3)
            .ignoreIdleThreads(false);

        // Set up the mocks
        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);

        long[] topThreadIds = new long[]{threadIds[threadIds.length - 1], threadIds[threadIds.length - 2]};
        List<ThreadInfo> topThreads = List.of(
            allInfos.get(threadIds.length - 1),
            allInfos.get(threadIds.length - 2));

        when(mockedMXBean.getThreadInfo(Matchers.any(long[].class), anyInt())).thenReturn(topThreads.toArray(new ThreadInfo[0]));

        ThreadInfo[][] infosWithStacks = hotThreads.captureThreadStacks(mockedMXBean, topThreadIds);

        assertEquals(3, infosWithStacks.length);
        for (ThreadInfo[] infos : infosWithStacks) {
            assertEquals(2, infos.length);
            assertEquals(threadIds[threadIds.length - 1], infos[0].getThreadId());
            assertEquals(threadIds[threadIds.length - 2], infos[1].getThreadId());
        }
    }

    public void testThreadInfoAccumulator() {
        ThreadMXBean mockedMXBean = mock(ThreadMXBean.class);
        when(mockedMXBean.isThreadCpuTimeSupported()).thenReturn(true);

        ThreadInfo threadOne = makeThreadInfoMocksHelper(mockedMXBean, 1L);
        ThreadInfo threadTwo = makeThreadInfoMocksHelper(mockedMXBean, 2L);

        TimeValue maxTime = new TimeValue(1000L);

        HotThreads.ThreadTimeAccumulator acc = new HotThreads.ThreadTimeAccumulator(
            threadOne, maxTime, 100L, 1000L);
        HotThreads.ThreadTimeAccumulator accNext = new HotThreads.ThreadTimeAccumulator(
            threadOne, maxTime,  250L, 2500L);
        accNext.subtractPrevious(acc);

        assertEquals(1500, accNext.getAllocatedBytes());
        assertEquals(150, accNext.getCpuTime());
        assertEquals(0, accNext.getWaitedTime());
        assertEquals(nanosHelper(1), accNext.getBlockedTime());
        assertEquals(nanosHelper(999), accNext.getRunnableTime());

        HotThreads.ThreadTimeAccumulator accNotMoving = new HotThreads.ThreadTimeAccumulator(
            threadOne, maxTime, 250L, 2500L);
        HotThreads.ThreadTimeAccumulator accNotMovingNext = new HotThreads.ThreadTimeAccumulator(
            threadOne, maxTime, 250L, 2500L);

        accNotMovingNext.subtractPrevious(accNotMoving);

        assertEquals(0, accNotMovingNext.getAllocatedBytes());
        assertEquals(0, accNotMovingNext.getCpuTime());
        assertEquals(0, accNotMovingNext.getWaitedTime());
        assertEquals(0, accNotMovingNext.getBlockedTime());
        assertEquals(0, accNotMovingNext.getRunnableTime());

        HotThreads.ThreadTimeAccumulator accOne = new HotThreads.ThreadTimeAccumulator(
            threadOne, maxTime, 250L, 2500L);
        HotThreads.ThreadTimeAccumulator accTwo = new HotThreads.ThreadTimeAccumulator(
            threadTwo, maxTime, 350L, 3500L);

        expectThrows(IllegalStateException.class, () -> accTwo.subtractPrevious(accOne));
    }

    public void testWaitBlockTimeMonitoringEnabled() throws Exception {
        ThreadMXBean mockedMXBean = mock(ThreadMXBean.class);
        when(mockedMXBean.isThreadCpuTimeSupported()).thenReturn(true);
        when(mockedMXBean.isThreadContentionMonitoringSupported()).thenReturn(true);

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        long mockCurrentThreadId = 0L;
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        List<ThreadInfo> cpuOrderedInfos = List.of(allInfos.get(3), allInfos.get(2), allInfos.get(1), allInfos.get(0));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(cpuOrderedInfos.toArray(new ThreadInfo[0]));

        HotThreads hotThreads = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.CPU)
            .interval(TimeValue.timeValueNanos(10))
            .threadElementsSnapshotCount(11)
            .ignoreIdleThreads(false);

        Exception e = expectThrows(ElasticsearchException.class, () ->
            hotThreads.innerDetect(mockedMXBean, mock(SunThreadInfo.class), mockCurrentThreadId, (interval) -> null));
        assertEquals(e.getMessage(), "thread wait/blocked time accounting is not supported on this JDK");

        // Ensure we checked if JVM lock monitoring is enabled
        InOrder orderVerifier = inOrder(mockedMXBean);
        orderVerifier.verify(mockedMXBean).isThreadContentionMonitoringSupported();
        orderVerifier.verify(mockedMXBean).isThreadContentionMonitoringEnabled();
    }

    public void testGetThreadAllocatedBytesFailures() throws Exception {
        ThreadMXBean mockedMXBean = mock(ThreadMXBean.class);
        when(mockedMXBean.isThreadCpuTimeSupported()).thenReturn(true);
        when(mockedMXBean.isThreadContentionMonitoringSupported()).thenReturn(true);
        when(mockedMXBean.isThreadContentionMonitoringEnabled()).thenReturn(true);

        SunThreadInfo mockedSunThreadInfo = mock(SunThreadInfo.class);
        when(mockedSunThreadInfo.isThreadAllocatedMemorySupported()).thenReturn(false);

        long[] threadIds = new long[]{1, 2, 3, 4}; // Adds up to 10, the intervalNanos for calculating time percentages
        long mockCurrentThreadId = 0L;
        when(mockedMXBean.getAllThreadIds()).thenReturn(threadIds);

        List<ThreadInfo> allInfos = makeThreadInfoMocksHelper(mockedMXBean, threadIds);
        List<ThreadInfo> cpuOrderedInfos = List.of(allInfos.get(3), allInfos.get(2), allInfos.get(1), allInfos.get(0));
        when(mockedMXBean.getThreadInfo(Matchers.any(), anyInt())).thenReturn(cpuOrderedInfos.toArray(new ThreadInfo[0]));

        HotThreads hotThreads0 = new HotThreads()
            .busiestThreads(4)
            .type(HotThreads.ReportType.MEM)
            .interval(TimeValue.timeValueNanos(10))
            .threadElementsSnapshotCount(1)
            .ignoreIdleThreads(false);

        ElasticsearchException exception = expectThrows(ElasticsearchException.class,
            () -> hotThreads0.innerDetect(mockedMXBean, mockedSunThreadInfo, 0L, (interval) -> null));
        assertThat(exception.getMessage(), equalTo("thread allocated memory is not supported on this JDK"));
    }
}
