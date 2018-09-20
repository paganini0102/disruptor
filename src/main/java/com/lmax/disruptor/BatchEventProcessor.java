/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExceptionHandler<? super T> exceptionHandler = new FatalExceptionHandler();
    private final DataProvider<T> dataProvider;
    private final SequenceBarrier sequenceBarrier;
    private final EventHandler<? super T> eventHandler;
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final TimeoutHandler timeoutHandler;
    private final BatchStartAware batchStartAware;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware =
                (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler =
                (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        running.set(false);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get();
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
    	// 线程已经启动
        if (!running.compareAndSet(false, true))
        {
            throw new IllegalStateException("Thread is already running");
        }
        // 清除中断状态
        sequenceBarrier.clearAlert();
        // 判断一下消费者是否实现了LifecycleAware，如果实现了这个接口，那么此时会发送一个启动通知
        notifyStart();

        // 定义一个event
        T event = null;
        // 获取要申请的序列
        long nextSequence = sequence.get() + 1L;
        try
        {
        	// 循环处理事件。除非超时或者中断。
            while (true)
            {
                try
                {
                	// 根据等待策略来等待可用的序列值。 
                    final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                    if (batchStartAware != null)
                    {
                        batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                    }

                    // 根据可用的序列值获取事件。批量处理nextSequence到availableSequence之间的事件。
                    while (nextSequence <= availableSequence)
                    {
                    	// 获取事件
                        event = dataProvider.get(nextSequence);
                        // 触发事件
                        eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                        nextSequence++;
                    }
                    // 设置事件处理者处理到的序列值。事件发布者会根据availableSequence判断是否发布事件
                    sequence.set(availableSequence);
                }
                catch (final TimeoutException e)
                {
                	// 超时异常
                    notifyTimeout(sequence.get());
                }
                catch (final AlertException ex)
                {
                	// 中断异常
                    if (!running.get())
                    {
                        break;
                    }
                }
                catch (final Throwable ex)
                {
                	// 这里可能用户消费者事件出错。如果自己实现了ExceptionHandler那么就不会影响继续消费
                    exceptionHandler.handleEventException(ex, nextSequence, event);
                    // 如果出现异常则设置为nextSequence
                    sequence.set(nextSequence);
                    nextSequence++;
                }
            }
        }
        finally
        {
            notifyShutdown();
            running.set(false);
        }
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}