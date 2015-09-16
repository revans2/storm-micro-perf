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

import java.util.Map;
import java.util.Collection;

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

import java.util.concurrent.TimeUnit;

/**
 * A single consumer queue that uses the LMAX Disruptor. They key to the performance is
 * the ability to catch up to the producer by processing tuples in batches.
 */
public class LatestDisruptorQ implements Q {
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

    private final int _batchSize;
    
    private RingBuffer<MutableObject> _buffer;
    private Sequence _consumer;
    private SequenceBarrier _barrier;
    
    private static String PREFIX = "disruptor-";
    private String _queueName = "";

    private long _waitTimeout;
    
    public static LatestDisruptorQ make(String name, int size, Map<String, String> conf) {
        long timeout = Q.getLong(conf, "Q.disruptor.timeout", 1000l);
        int batch = Q.getInt(conf, "Q.disruptor.batch", 1);
        boolean useLite = conf.containsKey("Q.disruptor.lite-blocking");

        return new LatestDisruptorQ(name, size, timeout, batch, useLite);
    }

    public LatestDisruptorQ(String queueName, int size, long timeout, int batchSize, boolean useLite) {
        this._queueName = PREFIX + queueName;
        WaitStrategy wait = null;
        if (timeout == 0) {
            wait = useLite ? new LiteBlockingWaitStrategy() : new BlockingWaitStrategy();
        } else {
            wait = new TimeoutBlockingWaitStrategy(timeout, TimeUnit.MILLISECONDS);
        }
        _batchSize = batchSize;
        _buffer = RingBuffer.createMultiProducer(new ObjectEventFactory(), size, wait);
        _consumer = new Sequence();
        _barrier = _buffer.newBarrier();
        _buffer.addGatingSequences(_consumer);
        _waitTimeout = timeout;
    }
    
    public String getName() {
      return _queueName;
    }

    public String toString() {
      return getName();
    }
   
    @Override 
    public void consumeBatch(EventHandler<Object> handler) {
        consumeBatchToCursor(_barrier.getCursor(), handler);
    }
    
    @Override
    public void consumeBatchWhenAvailable(EventHandler<Object> handler) {
        try {
            final long origSeq = _consumer.get();
            final long nextSequence = origSeq + _batchSize;
            long availableSequence = 0;
            try {
                availableSequence = _barrier.waitFor(nextSequence);
            } catch (TimeoutException te) {
                availableSequence = _barrier.getCursor();
            }
            if (availableSequence - origSeq > 0) {
                consumeBatchToCursor(availableSequence, handler);
            }
        } catch (AlertException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    
    private void consumeBatchToCursor(long cursor, EventHandler<Object> handler) {
        for(long curr = _consumer.get() + 1; curr <= cursor; curr++) {
            try {
                MutableObject mo = _buffer.get(curr);
                Object o = mo.o;
                mo.setObject(null);
                handler.onEvent(o, curr, curr == cursor);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        _consumer.set(cursor);
    }

    @Override
    public void publish(Collection<Object> objs) {
        int size = objs.size();
        if (size > 0) {
            long end = _buffer.next(size);
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

    public void publish(Object obj) {
        try {
            publish(obj, true);
        } catch (InsufficientCapacityException ex) {
            throw new RuntimeException("This code should be unreachable!");
        }
    }
    
    public void tryPublish(Object obj) throws InsufficientCapacityException {
        publish(obj, false);
    }
    
    public void publish(Object obj, boolean block) throws InsufficientCapacityException {
        try {
        final long id;
        if (block) {
            id = _buffer.next();
        } else {
            id = _buffer.tryNext(1);
        }
        final MutableObject m = _buffer.get(id);
        m.setObject(obj);
        _buffer.publish(id);
        } catch (storm.perf.com.lmax.disruptor.InsufficientCapacityException ice) {
            throw InsufficientCapacityException.INSTANCE;
        }
    }
   
    public long getCursor() {
        return _buffer.getCursor();
    }
    public long  size() { return (writePos() - readPos()); }
    public long  capacity()   { return _buffer.getBufferSize(); }
    public long  writePos()   { return _buffer.getCursor(); }
    public long  readPos()    { return _consumer.get(); }
    public float pctFull()    { return (1.0F * size() / capacity()); }
 
    public static class ObjectEventFactory implements EventFactory<MutableObject> {
        @Override
        public MutableObject newInstance() {
            return new MutableObject();
        }        
    }

    @Override
    public void close() {
        //NOOP
    }
}
