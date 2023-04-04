/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server;

import lombok.Synchronized;

import java.util.*;
import java.util.stream.Collectors;

public class AverageMap<K> {
    private static final double DISCARD_SMALLER_FACTOR = 0.7;

    private final int valuesToKeep;

    private final Map<K, List<Long>> valueMap = new HashMap<>();

    public AverageMap(int valuesToKeep) {
        this.valuesToKeep = valuesToKeep;
    }

    @Synchronized
    public void putValue(K key, long value) {
        List<Long> values = getValues(key);
        if(values.size() > 0) {
            long previousValue = values.get(0);
            if(value * DISCARD_SMALLER_FACTOR > previousValue) {
                values.remove(0);
            }
            else if(previousValue * DISCARD_SMALLER_FACTOR > value) {
                return;
            }
            if(values.size() == valuesToKeep) {
                values.remove(0);
            }
        }
        values.add(value);
    }

    @Synchronized
    public Long getAverage(K key) {
        List<Long> values = getValues(key);
        if(values == null || values.size() == 0) { return null; }
        return (long) values.stream().mapToLong(l -> l).average().getAsDouble();
    }
    @Synchronized
    public Map<K, Long> getAverages() {
        Map<K, Long> averages = new HashMap<>(valueMap.size() + 1);
        valueMap.entrySet().stream().forEach(
                entry -> {
                    if(entry.getValue() != null && entry.getValue().size() > 0) {
                        averages.put(entry.getKey(), (long)
                                entry.getValue().stream().mapToLong(l -> l).average().getAsDouble());
                    }
                }
        );
        return averages;
    }

    @Synchronized
    private List<Long> getValues(K key) {
        if(key == null) { throw new NullPointerException(); }
        return valueMap.computeIfAbsent(key, k -> new ArrayList<>(valuesToKeep));
    }
}
