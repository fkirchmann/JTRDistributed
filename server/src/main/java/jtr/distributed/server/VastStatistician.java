/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server;

import com.esotericsoftware.minlog.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class VastStatistician {
    private static final String LOG_VAST = "gpu-stats";
    public static final File SAVED_STATE = new File("gpu-stats.json"),
                             SAVED_STATE_TMP = new File("gpu-stats.tmp.json");

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class GpuStatistics {
        private Map<String, Long> clientStatistics = new HashMap<>();
        private long averageHashrate;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class PersistentStatistics {
        Map<KnownGPU, GpuStatistics> gpuStatistics = new HashMap<>();
        Map<String, Integer> clientIdToHostId = new HashMap<>();
        Map<Integer, KnownGPU> hostIdGPUs = new HashMap<>();
        Map<String, KnownGPU> clientIdGPUs = new HashMap<>();
    }

    private final PersistentStatistics state;
    private final AverageMap<String> clientAveragesMap;
    private final Set<String> unknownGpusWarnedAbout = new HashSet<String>();
    private final Set<String> unknownHostsWarnedAbout = new HashSet<String>();
    private final Object lock = new Object();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule());

    public VastStatistician(AverageMap<String> clientAveragesMap) {
        this.clientAveragesMap = clientAveragesMap;
        PersistentStatistics initialState = null;
        if(SAVED_STATE.isFile()) {
            try {
                initialState = mapper.readValue(SAVED_STATE, PersistentStatistics.class);
            } catch (IOException e) {
                Log.warn(LOG_VAST, "Could not read saved state", e);
            }
        }
        if(initialState == null) { initialState = new PersistentStatistics(); }
        this.state = initialState;

        Thread updaterThread = new Thread(new Runnable() {
            @Override
            @SneakyThrows(InterruptedException.class)
            public void run() {
                while (true) {
                    synchronized (lock) {
                        lock.wait();
                        // Step one: sort new (and refresh existing) client-provided hashrates into GPU buckets
                        for(Map.Entry<String, Long> entry : clientAveragesMap.getAverages().entrySet()) {
                            final String clientId = entry.getKey();
                            final Long hashrate = entry.getValue();
                            if(clientId == null || hashrate == null) { continue; }
                            KnownGPU gpu = state.clientIdGPUs.get(clientId);
                            if(gpu == null) { continue; }
                            GpuStatistics statistics =
                                    state.gpuStatistics.computeIfAbsent(gpu, g -> new GpuStatistics(
                                            new HashMap<>(), entry.getValue()
                                    ));
                            statistics.clientStatistics.put(clientId, hashrate);
                        }
                        // step two: calculate average for each GPU bucket
                        for(GpuStatistics statistics : state.gpuStatistics.values()) {
                            statistics.averageHashrate = (long) statistics.clientStatistics.values().stream()
                                    .mapToLong(l -> l).average().getAsDouble();
                        }
                        try {
                            mapper.writeValue(SAVED_STATE_TMP, state);
                            if(SAVED_STATE.exists() && !SAVED_STATE.delete()) { throw new IOException("Deleting old state failed"); }
                            if(!SAVED_STATE_TMP.renameTo(SAVED_STATE)) { throw new IOException("Rename failed"); }
                        } catch (IOException e) {
                            Log.warn(LOG_VAST, "Could not save state!", e);
                        }
                    }
                }
            }
        });
        updaterThread.setDaemon(true);
        updaterThread.setName("VastStatistician");
        updaterThread.start();
    }

    @Synchronized("lock")
    public void updateClientGPUAndRecalculate(String clientId, String gpuName) {
        if(clientId == null || gpuName == null) {
            Log.warn(LOG_VAST, "Attempted null gpu update: " + clientId + " / " + gpuName);
            return;
        }
        KnownGPU gpu = KnownGPU.forName(gpuName);
        if(gpu == null) {
            if(!unknownGpusWarnedAbout.contains(gpuName)) {
                Log.warn(LOG_VAST, "Client " + clientId + " using unknown GPU \"" + gpuName
                        + "\", statistics won't work!");
                unknownGpusWarnedAbout.add(gpuName);
            }
            return;
        }
        Integer hostId = getHostId(clientId);
        if(hostId != null) {
            state.hostIdGPUs.put(hostId, gpu);
        }
        state.clientIdGPUs.put(clientId, gpu);
        lock.notifyAll();
    }

    @Synchronized("lock")
    public long estimateGpuHashrate(String clientId, Integer hostId, String vastGpuName, Long tflops) {
        Map<String, Long> clientAverages = clientAveragesMap.getAverages();
        KnownGPU gpu = null;
        if(clientId != null && clientId.trim().length() > 0) {
            for(Map.Entry<String, KnownGPU> clientIdGPUMapping : state.clientIdGPUs.entrySet()) {
                if(clientIdGPUMapping.getKey().contains(clientId)) {
                    if(hostId != null) {
                        state.clientIdToHostId.put(clientIdGPUMapping.getKey(), hostId);
                    }
                    gpu = clientIdGPUMapping.getValue();
                    if(hostId != null && gpu != null) {
                        state.hostIdGPUs.put(hostId, gpu);
                    }
                }
            }
        }
        if(gpu == null && hostId != null) {
            Log.trace(LOG_VAST, "Got Host " + hostId + " GPU hashrate from good stats :)");
            gpu = state.hostIdGPUs.get(hostId);
        }
        if(gpu == null && vastGpuName != null && vastGpuName.trim().length() > 0) {
            gpu = KnownGPU.forName(vastGpuName);
        }
        if(gpu != null) {
            GpuStatistics gpuStatistics = state.gpuStatistics.get(gpu);
            if(gpuStatistics != null) {
                Log.trace(LOG_VAST, "Got Client ID " + clientId + " GPU hashrate from good stats :)");
                List<Long> averages = new ArrayList<>(gpuStatistics.clientStatistics.size());
                if(clientId != null) {
                    // Attempt to get client-specific hashrate
                    return (long) gpuStatistics.clientStatistics.entrySet().stream()
                            .filter(e -> e.getKey().contains(clientId))
                            .mapToLong(Map.Entry::getValue)
                            .average().orElse(gpuStatistics.averageHashrate);
                } else if(hostId != null) {
                    // Attempt to get host-specific hashrate
                    return (long) gpuStatistics.clientStatistics.entrySet().stream()
                            .filter(e -> hostId.equals(getHostId(e.getKey())))
                            .mapToLong(Map.Entry::getValue)
                            .average().orElse(gpuStatistics.averageHashrate);
                } else {
                    // Just take the average value for this GPU
                    return gpuStatistics.averageHashrate;
                }
            }
            if(gpu.defaultHashrate != null) {
                Log.trace(LOG_VAST, "Got Client ID " + clientId + " GPU hashrate from fallback");
                return gpu.defaultHashrate;
            }
        }
        Log.warn(LOG_VAST, "Client \"" + clientId + "\", GPU: \"" + vastGpuName + "\""
                + (gpu != null ? "\", identified as " + gpu.name() : "")
                + ", host \"" + hostId + "\""
                +", guesstimating hashrate via flops");
        return tflops == null ? 0 : tflops * 80000;
    }

    @Synchronized("lock")
    private Integer getHostId(String clientId) {
        for(Map.Entry<String, Integer> mapping : state.clientIdToHostId.entrySet()) {
            if(mapping.getKey().contains(clientId)) {
                unknownHostsWarnedAbout.remove(clientId);
                return mapping.getValue();
            }
        }
        if(!unknownHostsWarnedAbout.contains(clientId)) {
            Log.trace(LOG_VAST, "Could not map client ID " + clientId /*+ " (sanitized: " + clientIdSanitized + ")"*/
                    + " to a host");
            unknownHostsWarnedAbout.add(clientId);
        }
        return null;
    }

    @Getter
    enum KnownGPU {
        // Pascal: 10**, Titan Xp
        // Volta: V100
        // Turing: RTX 20**, Titan RTX
        // Ampere: RTX 30**, A100
        GTX_1070("GTX 1070", 1549 * 1000L),
        GTX_1080("GTX 1080", 1847 * 1000L),
        GTX_1080_Ti("GTX 1080 Ti", 1850 * 1000L),
        RTX_2070("RTX 2070", 2067 * 1000L),
        RTX_2070S("RTX 2070S", 1758 * 1000L),
        RTX_2080("RTX 2080", 1956 * 1000L),
        RTX_2080S("RTX 2080S", null),
        RTX_2080_Ti("RTX 2080 Ti", 3570 * 1000L),
        Titan_RTX("Titan RTX", 4084 * 1000L),
        RTX_3070("RTX 3070", null),
        RTX_3080("RTX 3080", null),
        RTX_3090("RTX 3090", 3130 * 1000L),
        V100("V100", 1190 * 1000L), // with SXM2
        A100("A100", 2015 * 1000L);

        private final String name, nameLower;
        private final Long defaultHashrate;

        private KnownGPU(String name, Long defaultHashrate) {
            this.name = name;
            this.nameLower = name.replace(" ", "").toLowerCase();
            this.defaultHashrate = defaultHashrate;
        }

        public static KnownGPU forName(String name) {
            name = name.replace(" ", "").toLowerCase();
            KnownGPU longestMatch = null;
            for(KnownGPU gpu : KnownGPU.values()) {
                if(name.contains(gpu.nameLower) && (longestMatch == null
                        || gpu.nameLower.length() > longestMatch.nameLower.length())) {
                    longestMatch = gpu;
                }
            }
            return longestMatch;
        }
    }
}
