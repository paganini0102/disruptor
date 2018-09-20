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


/**
 * Coordination barrier for tracking the cursor for publishers and sequence of
 * dependent {@link EventProcessor}s for processing a data structure
 */
public interface SequenceBarrier
{
    /**
     * 等待一个序列变为可用，然后消费这个序列。消费线程中使用
     * Wait for the given sequence to be available for consumption.
     *
     * @param sequence to wait for
     * @return the sequence up to which is available
     * @throws AlertException       if a status change has occurred for the Disruptor
     * @throws InterruptedException if the thread needs awaking on a condition variable.
     * @throws TimeoutException     if a timeout occurs while waiting for the supplied sequence.
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /**
     * 获取当前可以读取的序列值。
     * Get the current cursor value that can be read.
     *
     * @return value of the cursor for entries that have been published.
     */
    long getCursor();

    /**
     * 当前栅栏是否发过通知。
     * The current alert status for the barrier.
     *
     * @return true if in alert otherwise false.
     */
    boolean isAlerted();

    /**
     * 通知消费者状态变化，然后停留在这个状态上，直到状态被清除。
     * Alert the {@link EventProcessor}s of a status change and stay in this status until cleared.
     */
    void alert();

    /**
     * 清楚通知状态。
     * Clear the current alert status.
     */
    void clearAlert();

    /**
     * 检测是否发生了通知，如果已经发生了抛出AlertException异常。
     * Check if an alert has been raised and throw an {@link AlertException} if it has.
     *
     * @throws AlertException if alert has been raised.
     */
    void checkAlert() throws AlertException;
}
