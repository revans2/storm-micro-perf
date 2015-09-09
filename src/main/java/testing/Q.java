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

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;

import java.util.Map;

/**
 * Provides an interface for different Queue implementations
 */
public interface Q {
    public String getName();
    public void consumeBatch(EventHandler<Object> handler);
    public void consumeBatchWhenAvailable(EventHandler<Object> handler);
    public void publish(Object obj);
    public void tryPublish(Object obj) throws InsufficientCapacityException;

    public static int getInt(Map<String, String> conf, String name, int defaultValue) {
        String tmp = conf.get(name);
        if (tmp != null) return Integer.valueOf(tmp);
        return defaultValue;
    }

    public static long getLong(Map<String, String> conf, String name, long defaultValue) {
        String tmp = conf.get(name);
        if (tmp != null) return Long.valueOf(tmp);
        return defaultValue;
    }

    static int roundUpToNextPowerOfTwo(int x) {
        x--;
        x |= x >> 1;  // handle  2 bit numbers
        x |= x >> 2;  // handle  4 bit numbers
        x |= x >> 4;  // handle  8 bit numbers
        x |= x >> 8;  // handle 16 bit numbers
        x |= x >> 16; // handle 32 bit numbers
        x++;
        return x;
    }

    public static Q make(String name, Map<String, String> conf) {
        return Q.make(name, conf, null);
    }

    public static Q make(String name, Map<String, String> conf, Integer sizeOverride) {
        int size = getInt(conf, "Q.size", 1024);
        if (sizeOverride != null) {
            size = sizeOverride;
        }
        size = roundUpToNextPowerOfTwo(size);
        String type = conf.get("Q.type");
        if ("storm".equalsIgnoreCase(type)) {
            return StormQueue.make(name, size, conf);
        } else if ("java-array".equalsIgnoreCase(type)) {
            return JavaArrayBlockingQueue.make(name, size, conf);
        } else if ("java-linked".equalsIgnoreCase(type)) {
            return JavaLinkedBlockingQueue.make(name, size, conf);
        } else if (type == null || "disruptor".equalsIgnoreCase(type)) {
            return DisruptorQueue.make(name, size, conf);
        }
        throw new IllegalArgumentException(type+" is not a supported Q type. [\"storm\", \"disruptor\", \"java-array\", \"java-linked\"]");
    }
}
