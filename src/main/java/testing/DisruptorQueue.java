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

import com.lmax.disruptor.AlertException;
import com.lmax.disruptor.ClaimStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.MultiThreadedClaimStrategy;
import com.lmax.disruptor.BatchDescriptor;

import java.util.concurrent.TimeUnit;

/**
 * A single consumer queue that uses the LMAX Disruptor. They key to the performance is
 * the ability to catch up to the producer by processing tuples in batches.
 */
public class DisruptorQueue implements Q {
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
    
    public static DisruptorQueue make(String name, int size, Map<String, String> conf) {
        long timeout = Q.getLong(conf, "Q.disruptor.timeout", 1000l);
        int batch = Q.getInt(conf, "Q.disruptor.batch", 1);

        return new DisruptorQueue(name, size, timeout, batch);
    }

    public DisruptorQueue(String queueName, int size, long timeout, int batchSize) {
        this._queueName = PREFIX + queueName;
        ClaimStrategy claim = new MultiThreadedClaimStrategy(size);
        WaitStrategy wait = new BlockingWaitStrategy() ;
        _batchSize = batchSize;
        _buffer = new RingBuffer<MutableObject>(new ObjectEventFactory(), claim, wait);
        _consumer = new Sequence();
        _barrier = _buffer.newBarrier();
        _buffer.setGatingSequences(_consumer);
        _waitTimeout = timeout;
    }

    @Override
    public void register(BpCb cb) {
        //Ignored
    }
 
    @Override
    public boolean isThrottled() {
        return false;
    }

    public String getName() {
      return _queueName;
    }

    public String toString() {
      return getName();
    }
    
    public void consumeBatch(EventHandler<Object> handler) {
        consumeBatchToCursor(_barrier.getCursor(), handler);
    }
    
    public void consumeBatchWhenAvailable(EventHandler<Object> handler) {
        try {
            final long origSeq = _consumer.get();
            final long nextSequence = origSeq + _batchSize;
            final long availableSequence =
                    _waitTimeout == 0L ? _barrier.waitFor(nextSequence) : _barrier.waitFor(nextSequence, _waitTimeout,
                            TimeUnit.MILLISECONDS);

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
        BatchDescriptor batchDescriptor = _buffer.newBatchDescriptor(objs.size());
        _buffer.next(batchDescriptor);
        long at = batchDescriptor.getStart();
        for (Object obj: objs) {
            MutableObject m = _buffer.get(at);
            m.setObject(obj);
            at++;
        }
        _buffer.publish(batchDescriptor);
    }
 
    @Override
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
        final long id;
        if(block) {
            id = _buffer.next();
        } else {
            id = _buffer.tryNext(1);
        }
        final MutableObject m = _buffer.get(id);
        m.setObject(obj);
        _buffer.publish(id);
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
