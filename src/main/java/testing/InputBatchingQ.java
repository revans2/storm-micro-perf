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

import java.util.ArrayList;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;

import java.util.concurrent.BlockingQueue;

public class InputBatchingQ implements Q {
    private final Q _q;
    private final int _batchSize;
    private final ConcurrentHashMap<Long, ArrayList<Object>> _batches = new ConcurrentHashMap<Long, ArrayList<Object>>();
    private final Timer _timer = new Timer(true);
    
    public InputBatchingQ(Q q, int batchSize) {
        _q = q;
        _batchSize = batchSize;
        _timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                for (ArrayList<Object> batch : _batches.values()) {
                    synchronized(batch) {
                        flushBatch(batch);
                    }
                }
            }
        }, 1, 1);
    }
    
    @Override
    public String getName() {
      return "BATCH_"+_batchSize+"_"+_q.getName();
    }

    @Override
    public String toString() {
      return getName();
    }
    
    @Override
    public void consumeBatch(EventHandler<Object> handler) {
        _q.consumeBatch(handler);
    }
    
    @Override
    public void consumeBatchWhenAvailable(EventHandler<Object> handler) {
        _q.consumeBatchWhenAvailable(handler);
    }
    
    private static Long getId() {
        return Thread.currentThread().getId();
    }

    //Should only be called with a lock held on batch
    public void flushBatch(ArrayList<Object> batch) {
        for (Object o: batch) {
            _q.publish(o);
        }
        batch.clear();
    }

    @Override
    public void publish(Object obj) {
        Long id = getId();
        ArrayList<Object> batch = _batches.get(id);
        if (batch == null) {
            batch = new ArrayList<Object>();
            _batches.put(id, batch);
        }
        synchronized (batch) {
            batch.add(obj);
            if (batch.size() >= _batchSize) {
                flushBatch(batch);
            }
        }
    }
    
    @Override
    public void tryPublish(Object obj) throws InsufficientCapacityException {
        //TODO figure this out, right now this allows for out of order delivery
        _q.tryPublish(obj);
    }

    @Override
    public void close() {
        _timer.cancel();
        _q.close();
    }
}
