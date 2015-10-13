/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package testing;

import storm.perf.com.lmax.disruptor.AlertException;
import storm.perf.com.lmax.disruptor.EventFactory;
import storm.perf.com.lmax.disruptor.RingBuffer;
import storm.perf.com.lmax.disruptor.Sequence;
import storm.perf.com.lmax.disruptor.SequenceBarrier;
import storm.perf.com.lmax.disruptor.WaitStrategy;
import storm.perf.com.lmax.disruptor.BlockingWaitStrategy;
import storm.perf.com.lmax.disruptor.LiteBlockingWaitStrategy;
import storm.perf.com.lmax.disruptor.TimeoutBlockingWaitStrategy;
import storm.perf.com.lmax.disruptor.TimeoutException;

//Old Code for compatability reasons
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import backtype.storm.metric.api.IStatefulObject;

/**
 * A single consumer queue that uses the LMAX Disruptor. They key to the performance is
 * the ability to catch up to the producer by processing tuples in batches.
 */
public class NBLatestDisruptorQ implements Q {
    public static class MutableObject {
        private Object o = null;
    
        public MutableObject() {
        
        }

        public MutableObject(Object o) {
            this.o = o;
        }
    
        public void setObject(Object o) {
            this.o = o;
        }
    
        public Object getObject() {
            return o;
        }
    }

    private static final Object INTERRUPT = new Object();
    private static final String PREFIX = "disruptor-";

    private static class ObjectEventFactory implements EventFactory<MutableObject> {
        @Override
        public MutableObject newInstance() {
            return new MutableObject();
        }
    }

    private class ThreadLocalBatcher {
        private final ReentrantLock _flushLock;
        private final ConcurrentLinkedQueue<ArrayList<Object>> _overflow;
        private ArrayList<Object> _currentBatch;

        public ThreadLocalBatcher() {
            _flushLock = new ReentrantLock();
            _overflow = new ConcurrentLinkedQueue<ArrayList<Object>>();
            _currentBatch = new ArrayList<Object>(_inputBatchSize);
        }

        //called by the main thread and should not block for an undefined period of time
        public synchronized void add(Object obj) {
            _currentBatch.add(obj);
            _overflowCount.incrementAndGet();
            if (_enableBackpressure && _cb != null && (_metrics.population() + _overflowCount.get()) >= _highWaterMark) {
                try {
                    if (!_throttleOn) {
                        _cb.highWaterMark();
                        _throttleOn = true;
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Exception during calling highWaterMark callback!", e);
                }
            }
            if (_currentBatch.size() >= _inputBatchSize) {
                boolean flushed = false;
                if (_overflow.isEmpty()) {
                    try {
                        publishDirect(_currentBatch, false);
                        _overflowCount.addAndGet(0 - _currentBatch.size());
                        _currentBatch.clear();
                        flushed = true;
                    } catch (storm.perf.com.lmax.disruptor.InsufficientCapacityException e) {
                        //Ignored we will flush later
                    }
                }

                if (!flushed) {        
                    _overflow.add(_currentBatch);
                    _currentBatch = new ArrayList<Object>(_inputBatchSize);
                }
            }
        }

        //May be called by a background thread
        public synchronized void forceBatch() {
            if (!_currentBatch.isEmpty()) {
                _overflow.add(_currentBatch);
                _currentBatch = new ArrayList<Object>(_inputBatchSize);
            }
        }

        //May be called by a background thread
        public void flush(boolean block) {
            if (block) {
                _flushLock.lock();
            } else if (!_flushLock.tryLock()) {
               //Someone else if flushing so don't do anything
               return;
            }
            try {
                while (!_overflow.isEmpty()) {
                    publishDirect(_overflow.peek(), block);
                    _overflowCount.addAndGet(0 - _overflow.poll().size());
                }
            } catch (storm.perf.com.lmax.disruptor.InsufficientCapacityException e) {
                //Ignored we should not block
            } finally {
                _flushLock.unlock();
            }
        }
    }

    private class FlusherThread extends Thread {
        private final long _flushInterval;
        private volatile boolean _done;

        public FlusherThread(long flushInterval, String name) {
            super(name+"-flusher");
            _flushInterval = flushInterval;
            _done = false;
            setDaemon(true);
        }

        public void run() {
            try {
                long nextFlushTime = System.currentTimeMillis();
                while (!_done) {
                    long now = System.currentTimeMillis();
                    if (now >= nextFlushTime) {
                        for (ThreadLocalBatcher batcher: _batchers.values()) {
                            batcher.forceBatch();
                            batcher.flush(true);
                        }
                        nextFlushTime = now + _flushInterval;
                    } else {
                        Thread.sleep(nextFlushTime - now);
                    }
                }
            } catch (InterruptedException e) {
                //Ignored we are done
            }
        }

        public void close() {
            _done = true;
            interrupt();
        }
    }

    /**
     * This inner class provides methods to access the metrics of the disruptor queue.
     */
    public class QueueMetrics {
        public long writePos() {
            return _buffer.getCursor();
        }

        public long readPos() {
            return _consumer.get();
        }

        public long overflow() {
            return _overflowCount.get();
        }

        public long population() {
            return writePos() - readPos();
        }

        public long capacity() {
            return _buffer.getBufferSize();
        }

        public float pctFull() {
            return (1.0F * population() / capacity());
        }
    }

    private final RingBuffer<MutableObject> _buffer;
    private final Sequence _consumer;
    private final SequenceBarrier _barrier;
    private final int _inputBatchSize;
    private final ConcurrentHashMap<Long, ThreadLocalBatcher> _batchers = new ConcurrentHashMap<Long, ThreadLocalBatcher>();
    private final FlusherThread _flusher;
    private final QueueMetrics _metrics;

    private String _queueName = "";
    private BpCb _cb = null;
    private int _highWaterMark = 0;
    private int _lowWaterMark = 0;
    private boolean _enableBackpressure = true;
    private final AtomicLong _overflowCount = new AtomicLong(0);
    private volatile boolean _throttleOn = false;

    public static NBLatestDisruptorQ make(String name, int size, int batch, Map<String, String> conf) {
        long timeout = Q.getLong(conf, "Q.disruptor.timeout", 1000l);
        int flushInterval = Q.getInt(conf, "Q.disruptor.flush-interval", 1);
        boolean useLite = conf.containsKey("Q.disruptor.lite-blocking");

        return new NBLatestDisruptorQ(name, size, timeout, batch, flushInterval, useLite);
    }

    public NBLatestDisruptorQ(String queueName, int size, long readTimeout, int inputBatchSize, long flushInterval, boolean useLite) {
        this._queueName = PREFIX + queueName;
        WaitStrategy wait = null;
        if (readTimeout <= 0 || useLite) {
            wait = new LiteBlockingWaitStrategy();
        } else {
            wait = new TimeoutBlockingWaitStrategy(readTimeout, TimeUnit.MILLISECONDS);
        }

        _buffer = RingBuffer.createMultiProducer(new ObjectEventFactory(), size, wait);
        _consumer = new Sequence();
        _barrier = _buffer.newBarrier();
        _buffer.addGatingSequences(_consumer);
        _metrics = new QueueMetrics();
        //The batch size can be no larger than half the full queue size.
        //This is mostly to avoid contention issues.
        _inputBatchSize = Math.max(1, Math.min(inputBatchSize, size/2));

        _flusher = new FlusherThread(Math.max(flushInterval, 1), _queueName);
        _flusher.start();
        setHighWaterMark(0.9);
        setLowWaterMark(0.4);
    }

    @Override
    public boolean isThrottled() {
        return _throttleOn;
    }

    public String getName() {
        return _queueName;
    }

    public boolean isFull() {
        return (_metrics.population() + _overflowCount.get()) >= _metrics.capacity();
    }

    @Override
    public void close() {
        try {
            publishDirect(new ArrayList<Object>(Arrays.asList(INTERRUPT)), true);
            _flusher.close();
            _flusher.join();
        } catch (storm.perf.com.lmax.disruptor.InsufficientCapacityException e) {
            //This should be impossible
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void consumeBatch(EventHandler<Object> handler) {
        if (_metrics.population() > 0) {
            consumeBatchWhenAvailable(handler);
        }
    }

    public void consumeBatchWhenAvailable(EventHandler<Object> handler) {
        try {
            final long nextSequence = _consumer.get() + 1;
            long availableSequence = availableSequence = _barrier.waitFor(nextSequence);

            if (availableSequence >= nextSequence) {
                consumeBatchToCursor(availableSequence, handler);
            }
        } catch (TimeoutException te) {
            //Ignored for now
        } catch (AlertException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void consumeBatchToCursor(long cursor, EventHandler<Object> handler) {
        for (long curr = _consumer.get() + 1; curr <= cursor; curr++) {
            try {
                MutableObject mo = _buffer.get(curr);
                Object o = mo.getObject();                
                mo.setObject(null);
                if (o == INTERRUPT) {
                    throw new InterruptedException("Disruptor processing interrupted");
                } else if (o == null) {
                    System.err.println("Got a null message "+ curr + " " + _queueName + " " + mo.getObject());
                } else {
                    handler.onEvent(o, curr, curr == cursor);
                    if (_enableBackpressure && _cb != null && (_metrics.writePos() - curr + _overflowCount.get()) <= _lowWaterMark) {
                        try {
                            if (_throttleOn) {
                                _throttleOn = false;
                                _cb.lowWaterMark();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Exception during calling lowWaterMark callback!");
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        //TODO: only set this if the consumer cursor has changed?
        _consumer.set(cursor);
    }

    @Override
    public void register(BpCb cb) {
        this._cb = cb;
    }

    private static Long getId() {
        return Thread.currentThread().getId();
    }

    private void publishDirect(ArrayList<Object> objs, boolean block) throws storm.perf.com.lmax.disruptor.InsufficientCapacityException {
        int size = objs.size();
        if (size > 0) {
            long end;
            if (block) {
                end = _buffer.next(size);
            } else {
                end = _buffer.tryNext(size);
            }
            long begin = end - (size - 1);
            long at = begin;
            for (Object obj: objs) {
                MutableObject m = _buffer.get(at);
                m.setObject(obj);
                at++;
            }
            _buffer.publish(begin, end);
        }
    }

    public void tryPublish(Object obj) {
        publish(obj);
    }

    public void publish(Object obj) {
        Long id = getId();
        ThreadLocalBatcher batcher = _batchers.get(id);
        if (batcher == null) {
            //This thread is the only one ever creating this, so this is safe
            batcher = new ThreadLocalBatcher();
            _batchers.put(id, batcher);
        }
        batcher.add(obj);
        batcher.flush(false);
    }

    public void publish(Collection<Object> objs) {
        Long id = getId();
        ThreadLocalBatcher batcher = _batchers.get(id);
        if (batcher == null) {
            //This thread is the only one ever creating this, so this is safe
            batcher = new ThreadLocalBatcher();
            _batchers.put(id, batcher);
        }
        for (Object obj: objs) {
            batcher.add(obj);
        }
        batcher.flush(false);
    }

    public Q setHighWaterMark(double highWaterMark) {
        this._highWaterMark = (int)(_metrics.capacity() * highWaterMark);
        return this;
    }

    public Q setLowWaterMark(double lowWaterMark) {
        this._lowWaterMark = (int)(_metrics.capacity() * lowWaterMark);
        return this;
    }

    public int getHighWaterMark() {
        return this._highWaterMark;
    }

    public int getLowWaterMark() {
        return this._lowWaterMark;
    }

    public Q setEnableBackpressure(boolean enableBackpressure) {
        this._enableBackpressure = enableBackpressure;
        return this;
    }

    //This method enables the metrics to be accessed from outside of the DisruptorQueue class
    public QueueMetrics getMetrics() {
        return _metrics;
    }
}
