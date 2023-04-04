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
import jtr.distributed.core.LocalTimeLogger;
import jtr.distributed.core.events.ErrorEvent;
import jtr.distributed.core.events.PasswordFoundEvent;
import jtr.distributed.core.events.PasswordNotFoundEvent;
import jtr.distributed.core.events.StatusEvent;
import jtr.distributed.core.wordlist.ExampleWordlistGenerator;
import jtr.distributed.core.wordlist.WordlistGenerator;
import jtr.distributed.server.assignment.ActiveAssignment;
import jtr.distributed.server.assignment.AssignmentManager;
import jtr.distributed.server.web.WebServer;
import lombok.*;
import org.glassfish.grizzly.utils.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class ServerMain {
    public static void main(String[] args) {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        // Change this to use a different wordlist
        // Also change it in jtr.distributed.client.ClientMain
        WordlistGenerator generator = new ExampleWordlistGenerator();

        Log.setLogger(new LocalTimeLogger());
        if(args.length >= 4 && args[3].equals("--trace")) {
            Log.set(Log.LEVEL_TRACE);
            Log.info("Setting level to trace");
        } else {
            Log.set(LOG_LEVEL);
        }
        new WebServer(host, port, new ServerMain(generator));
    }

    public static final long MIN_WORK_PACKAGE_SIZE =
            // In the best observed case, a RTX 3090 does 5015 Kp/s
            5L * 1000 * 1000
            // Scale a minimum work package so that it takes:
            // -  ~1 min on a RTX 3090 (5 Mp/s)
            // -  ~2 min on a Titan RTX (2.9 Mp/s)
            // -  ~4 min on a GTX 1080 Ti (1.8 Mp/s)
            * 1 * 60;

    public static final long MAX_WORK_PACKAGE_SIZE =
            // In the best observed case, a RTX 3090 does 5015 Kp/s
            5L * 1000 * 1000
            // Scale a maximum work package so that it takes:
            // -  ~20 min on a RTX 3090 (5 Mp/s)
            // -  ~36 min on a Titan RTX (2.9 Mp/s)
            // - ~132 min on a GTX 1080 Ti (1.8 Mp/s)
            * 20 * 60;

    // This is only used for the first assignment - once the client's hashrate is known, the work package is scaled
    // to aim at WORK_PACKAGE_TARGET_DURATION
    public static final long DEFAULT_WORK_PACKAGE_SIZE = MIN_WORK_PACKAGE_SIZE;

    public static final Duration WORK_PACKAGE_TARGET_DURATION = Duration.ofMinutes(8); // TODO: replace with 20 for prod

    public static final int LOG_LEVEL = Log.LEVEL_DEBUG;
    public static final String LOG_MAIN = "main";
    public static final File SAVED_STATE = new File("state.json"),
                             SAVED_STATE_TMP = new File("state.tmp.json");
    public static final Duration CLIENT_TIMEOUT_INITIAL = Duration.ofSeconds(60);
    public static final Duration CLIENT_TIMEOUT_AFTER_FIRST_STATUS = Duration.ofSeconds(10);
    public static final Duration MAINTENANCE_THREAD_INTERVAL = Duration.ofSeconds(5);

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @ToString
    public static class ClientInfo implements Cloneable {
        String clientId;

        long lastGuessesPerSecond = 0, lastActualGuessesPerSecond = 0, lastGuessedIndex = 0;

        Instant lastSeen = Instant.now();
        Instant lastStatusReport = null;

        String lastGuessedPassword = null, gpuModel = null;

        public void updateLastSeen() { lastSeen = Instant.now(); }

        @Override
        public ClientInfo clone() {
            return new ClientInfo(clientId, lastGuessesPerSecond, lastActualGuessesPerSecond, lastGuessedIndex,
                    lastSeen, lastStatusReport, lastGuessedPassword, gpuModel);
        }
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class SavedState {
        AssignmentManager assignmentManager;
        List<ClientInfo> clients;
        String passwordFound;
    }

    private final AssignmentManager assignmentManager;
    private final Map<String, ClientInfo> clients = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule());

    @Getter
    private final AverageMap<String> clientGuessesPerSecondAverages = new AverageMap<>(20);

    @Getter
    private final VastStatistician vastStatistician = new VastStatistician(clientGuessesPerSecondAverages);

    @Getter
    private final WordlistGenerator wordlistGenerator;

    @Getter
    private volatile String passwordFound = null;

    private final Object[] lock = new Object[0];

    private ServerMain(WordlistGenerator generator) {
        this.wordlistGenerator = generator;

        if(SAVED_STATE.isFile()) {
            try {
                SavedState state = mapper.readValue(SAVED_STATE, SavedState.class);
                synchronized (lock) {
                    assignmentManager = state.assignmentManager;
                    for(ClientInfo clientInfo : state.clients) {
                        clients.put(clientInfo.clientId, clientInfo);
                    }
                    passwordFound = state.passwordFound;
                }
            } catch (IOException e) {
                Log.error(LOG_MAIN, "Aborting server startup: saved state could not be read", e);
                throw new RuntimeException(e);
            }
        }
        else {
            assignmentManager = new AssignmentManager(wordlistGenerator.getSize());
        }

        Thread maintenanceThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(MAINTENANCE_THREAD_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                AssignmentManager assignmentManagerCopy = null;
                List<ClientInfo> clientsCopy = new ArrayList<>(clients.size());
                final Instant minimum = Instant.now().minus(CLIENT_TIMEOUT_INITIAL);
                final Instant minimumAfterStatus = Instant.now().minus(CLIENT_TIMEOUT_AFTER_FIRST_STATUS);
                synchronized (lock) {
                    sanityCheckAssignments(); // TODO remove
                    Iterator<ClientInfo> clientIterator = clients.values().iterator();
                    while (clientIterator.hasNext()) {
                        ClientInfo clientInfo = clientIterator.next();
                        if (clientInfo.lastStatusReport == null ?
                                  // No status report yet? Client has more time
                                  clientInfo.lastSeen.isBefore(minimum)
                                  // After the first status report, timeout is more strict
                                : clientInfo.lastSeen.isBefore(minimumAfterStatus
                        )) {
                            Log.info(clientInfo.clientId, "Timeout: Removing client");
                            clientIterator.remove();
                            if(assignmentManager.removeAssignment(clientInfo.clientId)) {
                                Log.info(clientInfo.clientId, "Timeout: Removing client assignment");
                            }
                        } else {
                            clientsCopy.add(clientInfo.clone());
                        }
                    }
                    sanityCheckAssignments(); // TODO remove
                    assignmentManagerCopy = assignmentManager.clone();
                    // Consolidate successive completed blocks
                    /*Iterator<WorkAssignment> assignmentIterator = workAssignments.values().iterator();
                    WorkAssignment last = null, current = null;
                    while(assignmentIterator.hasNext()) {
                        current = assignmentIterator.next();
                        if(last != null && last.clientID == null && current.clientID == null
                                    && last.endIndex == current.beginIndex) {
                            // Remove the consolidated assignment
                            assignmentIterator.remove();
                            // Expand this assignment
                            last.endIndex = current.endIndex;
                            // Also update the expansion in the copied list
                            workAssignmentsCopy.get(workAssignmentsCopy.size() - 1).endIndex = current.endIndex;
                        } else {
                            workAssignmentsCopy.add(current.clone());
                            last = current;
                        }
                    }
                    sanityCheckAssignments();*/ // TODO remove
                }
                SavedState savedState = new SavedState(assignmentManagerCopy, clientsCopy, passwordFound);
                try {
                    mapper.writeValue(SAVED_STATE_TMP, savedState);
                    if(SAVED_STATE.exists() && !SAVED_STATE.delete()) { throw new IOException("Deleting old state failed"); }
                    if(!SAVED_STATE_TMP.renameTo(SAVED_STATE)) { throw new IOException("Rename failed"); }
                } catch (IOException e) {
                    Log.warn(LOG_MAIN, "Could not save state!", e);
                }
            }
        });
        maintenanceThread.setDaemon(true);
        maintenanceThread.setName("maintenance");
        maintenanceThread.start();
    }

    public void updateFound(String clientId, PasswordFoundEvent event) {
        if(clientId == null) {
            clientId = "Unknonwn-Client";
        }
        if(event == null) {
            Log.warn(clientId, "Got a null PasswordFoundEvent!");
            return;
        }if(event.getPassword() == null) {
            Log.warn(clientId, "Got a PasswordFoundEvent with a null password!");
            return;
        }
        passwordFound = event.getPassword();
        Log.info(clientId, "PASSWORD FOUND: \"" + event.getPassword() + "\"");
        System.err.println("PASSWORD FOUND: \"" + event.getPassword() + "\"");
        try {
            Files.write(Paths.get("PASSWORD_FOUND.txt"),
                    (Instant.now().toString() + ": PASSWORD FOUND: \"" + event.getPassword() + "\"\r\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Synchronized("lock")
    public void updateStatus(String clientId, StatusEvent event) {
        sanityCheckAssignments(); // TODO remove
        ClientInfo clientInfo = getClientInfoOrException(clientId);
        ActiveAssignment activeAssignment = getActiveAssignmentOrException(clientInfo);
        if(event.getBeginIndex() < activeAssignment.getBeginIndex()) { throw new IllegalArgumentException(
                "BeginIndex must not be outside assignment"); }
        if(event.getEndIndex() > activeAssignment.getEndIndex()) { throw new IllegalArgumentException(
                "EndIndex must not be outside assignment"); }
        if(event.getEndIndex() < event.getBeginIndex()) { throw new IllegalArgumentException(
                "EndIndex must not be before BeginIndex"); }
        if(event.getLastGuessedIndex() < activeAssignment.getBeginIndex()
                || event.getLastGuessedIndex() >= activeAssignment.getEndIndex()) {
            throw new IllegalArgumentException("LastGuessedIndex " + event.getLastGuessedIndex()
                    + " must not be out of bounds: " + activeAssignment);
        }
        if(event.getLastGuessedIndex() == clientInfo.lastGuessedIndex) {
            Log.info(clientId, "Did not make any progress since last status update");
            clientInfo.updateLastSeen();
            clientInfo.lastGuessesPerSecond = event.getGuessesPerSecond();
            clientInfo.gpuModel = event.getGpuModel();
            return;
        }
        double percentProgress = ((double) event.getLastGuessedIndex() - event.getBeginIndex())
                                    / (event.getEndIndex() - event.getBeginIndex()) * 100.0;
        Log.trace(clientId, String.format("Update: [%,d / %,d / %,d) words (%.2f %%) @ %d kp/s, last: %s",
                event.getBeginIndex(), event.getLastGuessedIndex(), event.getEndIndex(),
                percentProgress, event.getGuessesPerSecond() / 1000,
                event.getLastGuessedPassword()).replace(',', '.'));

        if(clientInfo.lastStatusReport != null) {
            clientInfo.lastActualGuessesPerSecond = ((event.getLastGuessedIndex() - clientInfo.lastGuessedIndex)
                    / Duration.between(clientInfo.lastStatusReport, event.getTimestamp()).toMillis()) * 1000;
            clientGuessesPerSecondAverages.putValue(clientId, clientInfo.lastActualGuessesPerSecond);
            vastStatistician.updateClientGPUAndRecalculate(clientId, event.getGpuModel());
        }
        clientInfo.lastGuessesPerSecond = event.getGuessesPerSecond();
        clientInfo.lastGuessedIndex = event.getLastGuessedIndex();
        clientInfo.lastStatusReport = event.getTimestamp();

        clientInfo.lastGuessedPassword = event.getLastGuessedPassword();
        clientInfo.gpuModel = event.getGpuModel();
        clientInfo.updateLastSeen();
        //markPasswordNotFound(workAssignment, clientInfo, event.getBeginIndex(), event.getLastGuessedIndex() + 1);
        assignmentManager.markCompleted(event.getBeginIndex(), event.getLastGuessedIndex() + 1);
        sanityCheckAssignments(); // TODO remove
    }

    @Synchronized("lock")
    public void updateNotFound(String clientId, PasswordNotFoundEvent event) {
        sanityCheckAssignments(); // TODO remove
        ClientInfo clientInfo = getClientInfoOrException(clientId);
        ActiveAssignment activeAssignment = getActiveAssignmentOrException(clientInfo);

        if(event.getBeginIndex() < activeAssignment.getBeginIndex()) { throw new IllegalArgumentException(
                "BeginIndex must not be outside assignment " + activeAssignment); }
        if(event.getEndIndex() > activeAssignment.getEndIndex()) { throw new IllegalArgumentException(
                "EndIndex must not be outside assignment " + activeAssignment); }
        if(event.getEndIndex() < event.getBeginIndex()) { throw new IllegalArgumentException(
                "EndIndex must not be before BeginIndex"); }

        Log.debug(clientId, "Password not found in range " + activeAssignment);
        clientInfo.lastStatusReport = null;
        clientInfo.updateLastSeen();
        //markPasswordNotFound(workAssignment, clientInfo, event.getBeginIndex(), event.getEndIndex());
        assignmentManager.markCompleted(event.getBeginIndex(), event.getEndIndex());
        assignmentManager.removeAssignment(clientId);
        sanityCheckAssignments(); // TODO remove
    }

    @Synchronized("lock")
    public void updateError(String clientId, ErrorEvent event) {
        sanityCheckAssignments(); // TODO remove
        Log.warn(clientId, "Removing client, reported exception: " + event.getMessage(), event.getException());
        ClientInfo clientInfo = getClientInfoOrException(clientId);
        if(assignmentManager.removeAssignment(clientInfo.clientId)) {
            Log.warn(clientId, "Removed work assignment due to exception");
        }
        clients.remove(clientId);
        sanityCheckAssignments(); // TODO remove
    }

    @Synchronized("lock")
    private ClientInfo getClientInfoOrException(String clientId) {
        ClientInfo clientInfo = clients.get(clientId);
        if(clientInfo == null) { throw new ClientNotFoundException(clientId); }
        return clientInfo;
    }

    public static class ClientNotFoundException extends IllegalArgumentException {
        @Getter
        private final String clientId;

        public ClientNotFoundException(String clientId) {
            super("Could not find client \"" + clientId + "\"");
            this.clientId = clientId;
        }
    }

    @Synchronized("lock")
    private ActiveAssignment getActiveAssignmentOrException(ClientInfo clientInfo) {
        ActiveAssignment activeAssignment = getActiveAssignment(clientInfo);
        if(activeAssignment == null) { throw new IllegalArgumentException("Could not find assignment for client \""
                + clientInfo.getClientId() + "\""); }
        return activeAssignment;
    }

    @Synchronized("lock")
    public ActiveAssignment getActiveAssignment(ClientInfo clientInfo) {
        return assignmentManager.getAssignment(clientInfo.clientId);
    }

    /*@Synchronized("lock")
    private void markPasswordNotFound(WorkAssignment workAssignment, ClientInfo clientInfo, long beginIndex, long endIndex) {
        if(endIndex > workAssignment.endIndex) { throw new IllegalArgumentException(
                "EndIndex must not be outside assignment"); }
        if(endIndex < beginIndex) { throw new IllegalArgumentException(
                "EndIndex must not be before BeginIndex"); }

        // Okay, so let's update the beginning of the active assignment so that it's right after the
        // last guessed word. In any case the beginning will move:
        workAssignments.remove(workAssignment.beginIndex);
        // Check if there is still work left in the assignment
        if(endIndex < workAssignment.endIndex) {
            // Still work left in this assignment?
            // Update the beginIndex and re-add it to the assignments
            workAssignment.beginIndex = endIndex;
            workAssignments.put(workAssignment.beginIndex, workAssignment);
            clientInfo.currentAssignmentBeginIndex = workAssignment.beginIndex;
        } else {
            // All work done!
            clientInfo.currentAssignmentBeginIndex = null;
        }
        // Now, let's mark the finished part in the assignments
        // First, check if there is already a pre-existing assignment that we can just extend
        WorkAssignment completedAssignment = null;
        for(WorkAssignment as : workAssignments.values()) {
            // Oh shoot, we already skipped past any assignments that may be useable? Welp, terminate the loop then.
            if(as.beginIndex > endIndex) { break; }
                // Assignments that aren't finished can't be used for extension into our completed assignment
            if(as.clientID != null
                    // If the assignment ends before our completed assignment begins, skip it
                    || as.endIndex < beginIndex) { continue; }

            if(as.beginIndex <= endIndex && as.endIndex >= beginIndex) {
                // At this point, completedAssignment is an assignment that either directly neighbors or overlaps
                // with our completed assignment. Thus, it can be used for extension.
                completedAssignment = as;
                break;
            }
        }
        if(completedAssignment != null) {
            //if(endIndex < completedAssignment.endIndex) {
            //    Log.warn("Assignment of client " + workAssignment.clientID + ": previous completed assignment: ["
            //            + completedAssignment.beginIndex + ", " + completedAssignment.endIndex
            //            + "), but new only goes to " + endIndex);
            //}
            workAssignments.remove(completedAssignment.beginIndex);
            completedAssignment.beginIndex = Math.min(beginIndex, completedAssignment.beginIndex);
            completedAssignment.endIndex = Math.max(endIndex, completedAssignment.endIndex);
            workAssignments.put(completedAssignment.beginIndex, completedAssignment);
        } else {
            // No completed assignment exists. Let's create one!
            completedAssignment = new WorkAssignment(beginIndex, beginIndex, endIndex, null);
            workAssignments.put(completedAssignment.beginIndex, completedAssignment);
        }
    }*/

    @Synchronized("lock")
    public ActiveAssignment getOrCreateWorkAssignment(String clientID) {
        sanityCheckAssignments(); // TODO remove

        ClientInfo clientInfo = clients.get(clientID);
        if(clientInfo != null && getActiveAssignment(clientInfo) != null) {
            clientInfo.updateLastSeen();
            ActiveAssignment assignment = getActiveAssignment(clientInfo);
            Log.info(clientID, "Resumed assignment " + assignment);
            return assignment;
        }

        final long maxIndex = wordlistGenerator.getSize();
        long targetSize = DEFAULT_WORK_PACKAGE_SIZE;
        if(clientInfo != null && clientGuessesPerSecondAverages.getAverage(clientID) != null) {
            long targetHashrate = clientGuessesPerSecondAverages.getAverage(clientID);
            targetSize = Math.min(MAX_WORK_PACKAGE_SIZE, Math.max(MIN_WORK_PACKAGE_SIZE,
                    WORK_PACKAGE_TARGET_DURATION.getSeconds() * targetHashrate));
            Log.info(clientID, "New work assignment targets hashrate " + targetHashrate
                    + " p/s, targetSize: " + targetSize +", device: " + clientInfo.gpuModel);
        }

        ActiveAssignment assignment = assignmentManager.getOrCreateAssignment(clientID, targetSize);
        if(assignment == null) {
            Log.warn(clientID, "Rejected work request: no work assignments available!");
            return null;
        }
        if(clientInfo == null) {
            Log.debug(clientID, "Registering new client");
            clientInfo = new ClientInfo();
            clientInfo.clientId = clientID;
            clients.put(clientInfo.clientId, clientInfo);
        }
        clientInfo.updateLastSeen();

        Log.debug(clientID, "Assigned work: size " + assignment.size() + ", " + assignment);

        sanityCheckAssignments(); // TODO remove
        return assignment;
    }

    @Synchronized("lock")
    public Pair<AssignmentManager, Map<String, ClientInfo>> getState() {
        Map<String, ClientInfo> clientsCopy = new HashMap<>((int) (clients.size() * 1.2));
        for(ClientInfo clientInfo : clients.values()) {
            clientsCopy.put(clientInfo.clientId, clientInfo.clone());
        }
        return new Pair<>(assignmentManager.clone(), clientsCopy);
    }

    @Synchronized("lock")
    private void sanityCheckAssignments() {
        assignmentManager.sanityCheckAssignments();
        ClientInfo client = null;
        for(Map.Entry<String, ClientInfo> entry : clients.entrySet()) {
            client = entry.getValue();
            if(!entry.getKey().equals(client.clientId)) {
                Log.warn("", new IllegalStateException("Client \"" + client.clientId
                        + "\" is mapped by different key \"" + entry.getKey() + "\""));
            }
        }
        for(ActiveAssignment activeAssignment : assignmentManager.getActiveAssignments()) {
            if(!clients.containsKey(activeAssignment.getClientId())) {
                Log.warn("", "Could not find client for assignment " + activeAssignment);
            }
        }
    }
}
