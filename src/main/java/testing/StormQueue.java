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

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.MultiThreadedClaimStrategy;

import java.util.concurrent.TimeUnit;

/**
 * A single consumer queue that uses the LMAX Disruptor. They key to the performance is
 * the ability to catch up to the producer by processing tuples in batches.
 */
public class StormQueue implements Q {
    private backtype.storm.utils.DisruptorQueue _q;
    
    public static StormQueue make(String name, int size, Map<String, String> conf) {
        long timeout = Q.getLong(conf, "Q.storm.timeout", 1000l);

        return new StormQueue(name, size, timeout);
    }

    public StormQueue(String queueName, int size, long timeout) {
        _q = new backtype.storm.utils.DisruptorQueue(queueName, new MultiThreadedClaimStrategy(size),
          new BlockingWaitStrategy());
        //If compiling against 0.11.0-SNAPSHOT
        //_q = new backtype.storm.utils.DisruptorQueue(queueName, new MultiThreadedClaimStrategy(size),
        //  new BlockingWaitStrategy(), timeout);

        _q.consumerStarted();
    }

    @Override
    public void register(BpCb cb) {
        //Ignored
    }

    @Override
    public boolean isThrottled() {
        return false;
    }

    @Override
    public String getName() {
      return _q.getName();
    }

    @Override
    public String toString() {
      return _q.toString();
    }
    
    @Override
    public void consumeBatch(EventHandler<Object> handler) {
        _q.consumeBatch(handler);
    }
    
    @Override
    public void consumeBatchWhenAvailable(EventHandler<Object> handler) {
        _q.consumeBatchWhenAvailable(handler);
    }

    @Override
    public void publish(Collection<Object> objs) {
        for (Object obj: objs) {
            publish(obj);
        }
    }
    
    @Override
    public void publish(Object obj) {
        _q.publish(obj);
    }
    
    @Override
    public void tryPublish(Object obj) throws InsufficientCapacityException {
        _q.tryPublish(obj);
    }

    @Override
    public void close() {
        //NOOP
    }
}
