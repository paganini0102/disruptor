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
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 */
final class ProcessingSequenceBarrier implements SequenceBarrier
{
	// 等待策略
    private final WaitStrategy waitStrategy;
    private final Sequence dependentSequence;
    // 判断是否执行shutdown
    private volatile boolean alerted = false;
    // 代表的是写指针。代表事件发布者发布到那个位置
    private final Sequence cursorSequence;
    // SingleProducerSequencer或者MultiProducerSequencer的引用
    private final Sequencer sequencer;

    ProcessingSequenceBarrier(
        final Sequencer sequencer,
        final WaitStrategy waitStrategy,
        final Sequence cursorSequence,
        final Sequence[] dependentSequences)
    {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;
        if (0 == dependentSequences.length)
        {
            dependentSequence = cursorSequence;
        }
        else
        {
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    public long waitFor(final long sequence)
        throws AlertException, InterruptedException, TimeoutException
    {
    	// 检查是否中断
        checkAlert();
        // 根据不同的策略获取可用的序列
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
        // 判断申请的序列和可用的序列大小
        if (availableSequence < sequence)
        {
            return availableSequence;
        }
        // 如果是单线程生产者直接返回availableSequence
        // 多线程生产者判断是否可用，不可用返回sequence-1
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    @Override
    public long getCursor()
    {
    	// 获取当前序列
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted()
    {
    	// 判断是否中断
        return alerted;
    }

    @Override
    public void alert()
    {
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert()
    {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException
    {
        if (alerted)
        {
            throw AlertException.INSTANCE;
        }
    }
}