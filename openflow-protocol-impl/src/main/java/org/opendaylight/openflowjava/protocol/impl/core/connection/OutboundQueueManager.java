/*
 * Copyright (c) 2015 Pantheon Technologies s.r.o. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.openflowjava.protocol.impl.core.connection;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.opendaylight.openflowjava.protocol.api.connection.OutboundQueueHandler;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.BarrierInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.OfHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OutboundQueueManager<T extends OutboundQueueHandler> extends ChannelInboundHandlerAdapter implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OutboundQueueManager.class);

    /**
     * This is the default upper bound we place on the flush task running
     * a single iteration. We relinquish control after about this amount
     * of time.
     */
    private static final long DEFAULT_WORKTIME_MICROS = TimeUnit.MILLISECONDS.toMicros(100);

    /**
     * We re-check the time spent flushing every this many messages. We do this because
     * checking after each message may prove to be CPU-intensive. Set to Integer.MAX_VALUE
     * or similar to disable the feature.
     */
    private static final int WORKTIME_RECHECK_MSGS = 64;

    /**
     * We maintain a cache of this many previous queues for later reuse.
     */
    private static final int QUEUE_CACHE_SIZE = 4;

    private final Queue<OutboundQueueImpl> queueCache = new ArrayDeque<>(QUEUE_CACHE_SIZE);
    private final Queue<OutboundQueueImpl> activeQueues = new LinkedList<>();
    private final ConnectionAdapterImpl parent;
    private final InetSocketAddress address;
    private final long maxBarrierNanos;
    private final long maxWorkTime;
    private final int queueSize;
    private final T handler;

    /*
     * Instead of using an AtomicBoolean object, we use these two. It saves us
     * from allocating an extra object.
     */
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<OutboundQueueManager> FLUSH_SCHEDULED_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(OutboundQueueManager.class, "flushScheduled");
    private volatile int flushScheduled = 0;

    // Updated from netty only
    private long lastBarrierNanos = System.nanoTime();
    private OutboundQueueImpl currentQueue;
    private int nonBarrierMessages;
    private long lastXid = 0;

    // Passed to executor to request triggering of flush
    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flush();
        }
    };
    private final Runnable barrierRunnable = new Runnable() {
        @Override
        public void run() {
            barrier();
        }
    };

    OutboundQueueManager(final ConnectionAdapterImpl parent, final InetSocketAddress address, final T handler,
        final int queueSize, final long maxBarrierNanos) {
        this.parent = Preconditions.checkNotNull(parent);
        this.handler = Preconditions.checkNotNull(handler);
        Preconditions.checkArgument(queueSize > 0);
        this.queueSize = queueSize;
        Preconditions.checkArgument(maxBarrierNanos > 0);
        this.maxBarrierNanos = maxBarrierNanos;
        this.address = address;
        this.maxWorkTime = TimeUnit.MICROSECONDS.toNanos(DEFAULT_WORKTIME_MICROS);

        LOG.debug("Queue manager instantiated with queue size {}", queueSize);
        createQueue();
        scheduleBarrierTimer(lastBarrierNanos);
    }

    T getHandler() {
        return handler;
    }

    @Override
    public void close() {
        handler.onConnectionQueueChanged(null);
    }

    private void retireQueue(final OutboundQueueImpl queue) {
        if (queueCache.offer(queue)) {
            LOG.debug("Saving queue {} for later reuse", queue);
        } else {
            LOG.debug("Queue {} thrown away", queue);
        }
    }

    private void createQueue() {
        final long baseXid = lastXid;
        lastXid += queueSize + 1;

        final OutboundQueueImpl cached = queueCache.poll();
        final OutboundQueueImpl queue;
        if (cached != null) {
            queue = cached.reuse(baseXid);
            LOG.debug("Reusing queue {} as {} on channel {}", cached, queue, parent.getChannel());
        } else {
            queue = new OutboundQueueImpl(this, baseXid, queueSize + 1);
            LOG.debug("Allocated new queue {} on channel {}", queue, parent.getChannel());
        }

        activeQueues.add(queue);
        currentQueue = queue;
        handler.onConnectionQueueChanged(queue);
    }

    private void scheduleBarrierTimer(final long now) {
        long next = lastBarrierNanos + maxBarrierNanos;
        if (next < now) {
            LOG.debug("Attempted to schedule barrier in the past, reset maximum)");
            next = now + maxBarrierNanos;
        }

        final long delay = next - now;
        LOG.debug("Scheduling barrier timer {}us from now", TimeUnit.NANOSECONDS.toMicros(delay));
        parent.getChannel().eventLoop().schedule(barrierRunnable, next - now, TimeUnit.NANOSECONDS);
    }

    private void scheduleBarrierMessage() {
        final Long xid = currentQueue.reserveEntry(true);
        Verify.verifyNotNull(xid);

        currentQueue.commitEntry(xid, handler.createBarrierRequest(xid), null);
        LOG.debug("Barrier XID {} scheduled", xid);

        // We can see into the future when compared to flushEntry(), as that
        // codepath may be lagging behind on messages. Resetting the counter
        // here ensures that flushEntry() will not attempt to issue a flush
        // request. Note that we do not reset current time, as that should
        // reflect when we sent the message for real.
        nonBarrierMessages = 0;
    }

    /**
     * Flush an entry from the queue.
     *
     * @param now Time reference for 'now'. We take this as an argument, as
     *            we need a timestamp to mark barrier messages we see swinging
     *            by. That timestamp does not need to be completely accurate,
     *            hence we use the flush start time. Alternative would be to
     *            measure System.nanoTime() for each barrier -- needlessly
     *            adding overhead.
     *
     * @return Entry which was flushed, null if no entry is ready.
     */
    OfHeader flushEntry(final long now) {
        final OfHeader message = currentQueue.flushEntry();
        if (currentQueue.isFlushed()) {
            LOG.debug("Queue {} is fully flushed", currentQueue);
            createQueue();
        }

        if (message == null) {
            return null;
        }

        if (message instanceof BarrierInput) {
            LOG.debug("Barrier message seen, resetting counters");
            nonBarrierMessages = 0;
            lastBarrierNanos = now;
        } else {
            nonBarrierMessages++;
            if (nonBarrierMessages >= queueSize) {
                LOG.debug("Scheduled barrier request after {} non-barrier messages", nonBarrierMessages);
                scheduleBarrierMessage();
            }
        }

        return message;
    }

    /**
     * Invoked whenever a message comes in from the switch. Runs matching
     * on all active queues in an attempt to complete a previous request.
     *
     * @param message Potential response message
     * @return True if the message matched a previous request, false otherwise.
     */
    boolean onMessage(final OfHeader message) {
        LOG.debug("Attempting to pair message {} to a request", message);

        Iterator<OutboundQueueImpl> it = activeQueues.iterator();
        while (it.hasNext()) {
            final OutboundQueueImpl queue = it.next();
            final OutboundQueueEntry entry = queue.pairRequest(message);

            if (entry == null) {
                continue;
            }

            LOG.debug("Queue {} accepted response {}", queue, message);

            // This has been a barrier request, we need to flush all
            // previous queues
            if (entry.isBarrier() && activeQueues.size() > 1) {
                LOG.debug("Queue {} indicated request was a barrier", queue);

                it = activeQueues.iterator();
                while (it.hasNext()) {
                    final OutboundQueueImpl q = it.next();

                    // We want to complete all queues before the current one, we will
                    // complete the current queue below
                    if (!queue.equals(q)) {
                        LOG.debug("Queue {} is implied finished", q);
                        q.completeAll();
                        it.remove();
                        retireQueue(q);
                    } else {
                        break;
                    }
                }
            }

            if (queue.isFinished()) {
                LOG.debug("Queue {} is finished", queue);
                it.remove();
                retireQueue(queue);
            }

            return true;
        }

        LOG.debug("Failed to find completion for message {}", message);
        return false;
    }

    private void scheduleFlush() {
        if (parent.getChannel().isWritable()) {
            if (FLUSH_SCHEDULED_UPDATER.compareAndSet(this, 0, 1)) {
                LOG.trace("Scheduling flush task on channel {}", parent.getChannel());
                parent.getChannel().eventLoop().execute(flushRunnable);
            } else {
                LOG.trace("Flush task is already present on channel {}", parent.getChannel());
            }
        } else {
            LOG.trace("Channel {} is not writable, not issuing a flush", parent.getChannel());
        }
    }

    void ensureFlushing(final OutboundQueueImpl queue) {
        Preconditions.checkState(currentQueue.equals(queue));
        scheduleFlush();
    }

    /**
     * Periodic barrier check.
     */
    protected void barrier() {
        LOG.debug("Channel {} barrier timer expired", parent.getChannel());
        if (currentQueue == null) {
            LOG.debug("Channel shut down, not processing barrier");
            return;
        }

        final long now = System.nanoTime();
        final long sinceLast = now - lastBarrierNanos;
        if (sinceLast >= maxBarrierNanos) {
            LOG.debug("Last barrier at {} now {}, elapsed {}", lastBarrierNanos, now, sinceLast);
            // FIXME: we should be tracking requests/responses instead of this
            if (nonBarrierMessages == 0) {
                LOG.debug("No messages written since last barrier, not issuing one");
            } else {
                scheduleBarrierMessage();
            }
        }

        scheduleBarrierTimer(now);
    }

    /**
     * Perform a single flush operation.
     */
    protected void flush() {
        final long start = System.nanoTime();
        final long deadline = start + maxWorkTime;

        LOG.debug("Dequeuing messages to channel {}", parent.getChannel());

        long messages = 0;
        for (;; ++messages) {
            if (!parent.getChannel().isWritable()) {
                LOG.trace("Channel is no longer writable");
                break;
            }

            final OfHeader message = flushEntry(start);
            if (message == null) {
                LOG.trace("The queue is completely drained");
                break;
            }

            final Object wrapper;
            if (address == null) {
                wrapper = new MessageListenerWrapper(message, null);
            } else {
                wrapper = new UdpMessageListenerWrapper(message, null, address);
            }
            parent.getChannel().write(wrapper);

            /*
             * Check every WORKTIME_RECHECK_MSGS for exceeded time.
             *
             * XXX: given we already measure our flushing throughput, we
             *      should be able to perform dynamic adjustments here.
             *      is that additional complexity needed, though?
             */
            if ((messages % WORKTIME_RECHECK_MSGS) == 0 && System.nanoTime() >= deadline) {
                LOG.trace("Exceeded allotted work time {}us",
                        TimeUnit.NANOSECONDS.toMicros(maxWorkTime));
                break;
            }
        }

        if (messages > 0) {
            LOG.debug("Flushing {} message(s) to channel {}", messages, parent.getChannel());
            parent.getChannel().flush();
        }

        final long stop = System.nanoTime();
        LOG.debug("Flushed {} messages in {}us to channel {}",
                messages, TimeUnit.NANOSECONDS.toMicros(stop - start), parent.getChannel());

        /*
         * We are almost ready to terminate. This is a bit tricky, because
         * we do not want to have a race window where a message would be
         * stuck on the queue without a flush being scheduled.
         *
         * So we mark ourselves as not running and then re-check if a
         * flush out is needed. That will re-synchronized with other threads
         * such that only one flush is scheduled at any given time.
         */
        if (!FLUSH_SCHEDULED_UPDATER.compareAndSet(this, 1, 0)) {
            LOG.warn("Channel {} queue {} flusher found unscheduled", parent.getChannel(), this);
        }

        conditionalFlush();
    }


    /**
     * Schedule a queue flush if it is not empty and the channel is found
     * to be writable. May only be called from Netty context.
     */
    private void conditionalFlush() {
        if (!currentQueue.isEmpty()) {
            scheduleFlush();
        } else {
            LOG.trace("Queue is empty, no flush needed");
        }
    }

    private void conditionalFlush(final ChannelHandlerContext ctx) {
        Preconditions.checkState(ctx.channel().equals(parent.getChannel()), "Inconsistent channel %s with context %s", parent.getChannel(), ctx);
        conditionalFlush();
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        conditionalFlush(ctx);
    }

    @Override
    public void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
        conditionalFlush(ctx);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);

        long entries = 0;
        LOG.debug("Channel shutdown, flushing queue...");
        handler.onConnectionQueueChanged(null);

        final Throwable cause = new RejectedExecutionException("Channel disconnected");
        for (OutboundQueueImpl queue : activeQueues) {
            entries += queue.failAll(cause);
        }
        activeQueues.clear();

        LOG.debug("Flushed {} queue entries", entries);
    }

    @Override
    public String toString() {
        return String.format("Channel %s queue [flushing=%s]", parent.getChannel(), flushScheduled);
    }
}