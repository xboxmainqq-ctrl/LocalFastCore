package ru.nexora.ai;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;

/**
 * Immutable point-in-time JVM resource counters. These are process metrics,
 * not an estimate of Minecraft server TPS or native/direct-buffer memory.
 */
public record JvmResourceSnapshot(
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long nonHeapCommittedBytes,
        int liveThreadCount,
        int peakThreadCount,
        int daemonThreadCount,
        int availableProcessors,
        double systemLoadAverage
) {
    public JvmResourceSnapshot {
        if (heapUsedBytes < 0 || heapCommittedBytes < 0 || heapMaxBytes < -1
                || nonHeapUsedBytes < 0 || nonHeapCommittedBytes < 0
                || liveThreadCount < 0 || peakThreadCount < 0 || daemonThreadCount < 0
                || daemonThreadCount > liveThreadCount || availableProcessors < 1
                || (!Double.isFinite(systemLoadAverage) && systemLoadAverage != -1.0)) {
            throw new IllegalArgumentException("Invalid JVM resource snapshot");
        }
    }

    public static JvmResourceSnapshot capture() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return new JvmResourceSnapshot(
                Math.max(0L, heap.getUsed()), Math.max(0L, heap.getCommitted()), heap.getMax(),
                Math.max(0L, nonHeap.getUsed()), Math.max(0L, nonHeap.getCommitted()),
                threads.getThreadCount(), threads.getPeakThreadCount(), threads.getDaemonThreadCount(),
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }

    public double heapUsageRatio() {
        return heapMaxBytes <= 0 ? -1.0 : (double) heapUsedBytes / heapMaxBytes;
    }
}
