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

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;

import java.util.concurrent.BlockingQueue;

public class JavaBlockingQueue implements Q {
    private BlockingQueue _q;
    private String _name;
    private volatile long _seq = 0;
    
    public JavaBlockingQueue(String queueName, BlockingQueue q) {
        _q = q;
        _name = "JAVA_ARRAY_"+queueName;
    }
    
    @Override
    public String getName() {
      return _name;
    }

    @Override
    public String toString() {
      return _name;
    }
    
    @Override
    public void consumeBatch(EventHandler<Object> handler) {
        try {
            Object o = _q.take();
            handler.onEvent(o, _seq++, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    public void consumeBatchWhenAvailable(EventHandler<Object> handler) {
        Object o = _q.poll();
        if (o != null) {
            try {
                handler.onEvent(o, _seq++, true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    @Override
    public void publish(Object obj) {
        try {
            _q.put(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    public void tryPublish(Object obj) throws InsufficientCapacityException {
        if (!_q.offer(obj)) {
            throw InsufficientCapacityException.INSTANCE;
        }
    }
}
